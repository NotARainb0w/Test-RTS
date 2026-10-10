package com.testrts.rtsclient.task;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Services nearby furnaces, blast furnaces and smokers in one round: takes out finished output,
 * then loads them with smeltable items and fuel fetched from linked storage, and finally stores the
 * output (and anything fetched but not loaded). Run it again later to collect the results.
 *
 * <p>Ores and raw metals prefer blast furnaces, raw food prefers smokers, sand/cobblestone/clay and
 * whatever has no better furnace go to plain furnaces. Fuel: coal, charcoal, coal blocks, then
 * planks and logs as a fallback.
 */
public final class SmeltTask implements RtsTask {
    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_OUTPUT = 2;
    /** Furnaces this close to a linked container count as "near storage". */
    private static final int STORAGE_RADIUS = 8;
    private static final int MAX_RIGHT_CLICKS = 16;

    private enum Kind { FURNACE, BLAST, SMOKER }

    private enum Phase { PLAN, VISIT, STORE, DONE }

    private static final List<Item> ORES = List.of(Items.RAW_IRON, Items.RAW_GOLD, Items.RAW_COPPER,
            Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE, Items.GOLD_ORE, Items.DEEPSLATE_GOLD_ORE,
            Items.COPPER_ORE, Items.DEEPSLATE_COPPER_ORE, Items.NETHER_GOLD_ORE, Items.ANCIENT_DEBRIS);
    private static final List<Item> FOOD = List.of(Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON,
            Items.COD, Items.SALMON, Items.POTATO, Items.RABBIT, Items.KELP);
    private static final List<Item> BLOCKS = List.of(Items.SAND, Items.RED_SAND, Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE, Items.CLAY_BALL, Items.NETHERRACK);

    private final Map<BlockPos, Kind> furnaces = new LinkedHashMap<>();
    /** Item each furnace should get, decided up front from what storage has. */
    private final Map<BlockPos, Item> planned = new HashMap<>();
    private final List<BlockPos> route = new ArrayList<>();
    private final Set<Item> handled = new LinkedHashSet<>();
    private final Map<Item, Integer> collected = new LinkedHashMap<>();
    private Map<Item, Integer> initial = Map.of();
    private Phase phase = Phase.PLAN;
    private boolean hasBlast;
    private boolean hasSmoker;
    private ContainerVisitTask visit;
    private int visited;
    private int loaded;
    private String detail = "looking for furnaces";

    public SmeltTask() {
    }

    @Override
    public String label() {
        return "Smelt";
    }

    @Override
    public String detail() {
        if (this.visit != null) {
            return "furnace " + this.visited + "/" + this.furnaces.size() + " - " + this.visit.detail();
        }
        return this.detail;
    }

    @Override
    public Status tick(MinecraftClient client, TaskQueue queue) {
        PlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return Status.FAILED;
        }
        switch (this.phase) {
            case PLAN -> {
                findFurnaces(client.world, player.getBlockPos());
                if (this.furnaces.isEmpty()) {
                    Messages.warn("No furnaces, blast furnaces or smokers within " + RtsConfig.get().scanRadius + " blocks");
                    return Status.DONE;
                }
                this.initial = inventoryCounts(player.getInventory());
                Map<Item, Integer> fetch = plan(player.getInventory());
                planRoute(player.getBlockPos());
                this.phase = Phase.VISIT;
                if (!fetch.isEmpty()) {
                    this.detail = "fetching inputs and fuel";
                    queue.pushFront(new FetchTask(fetch));
                }
                return Status.RUNNING;
            }
            case VISIT -> {
                if (this.visit != null) {
                    if (this.visit.tick(client, queue) == Status.RUNNING) {
                        return Status.RUNNING;
                    }
                    this.visit = null;
                }
                if (this.route.isEmpty()) {
                    this.phase = Phase.STORE;
                    return Status.RUNNING;
                }
                BlockPos next = this.route.remove(0);
                this.visited++;
                FurnaceWork work = new FurnaceWork(this.furnaces.get(next), this.planned.get(next));
                this.visit = new ContainerVisitTask(next, "Smelt", work::run);
                return Status.RUNNING;
            }
            case STORE -> {
                this.phase = Phase.DONE;
                int out = this.collected.values().stream().mapToInt(Integer::intValue).sum();
                Messages.success("Smelting: collected " + out + " item(s), loaded " + this.loaded + " of "
                        + this.furnaces.size() + " furnace(s)");
                if (StorageManager.get().linked().isEmpty()) {
                    if (out > 0) {
                        Messages.warn("No linked storage - the output stays in your inventory");
                    }
                    return Status.DONE;
                }
                Set<Item> store = new HashSet<>(this.handled);
                store.addAll(this.collected.keySet());
                queue.pushFront(new StoreItemsTask("Store smelting", s -> store.contains(s.getItem()), this.initial, true));
                return Status.RUNNING;
            }
            default -> {
                return Status.DONE;
            }
        }
    }

    // ------------------------------------------------------------------ planning

    private void findFurnaces(ClientWorld world, BlockPos player) {
        scanAround(world, player, RtsConfig.get().scanRadius);
        for (StorageManager.LinkedContainer c : StorageManager.get().linked()) {
            if (world.isChunkLoaded(c.pos())) {
                scanAround(world, c.pos(), STORAGE_RADIUS);
            }
        }
        for (Kind k : this.furnaces.values()) {
            this.hasBlast |= k == Kind.BLAST;
            this.hasSmoker |= k == Kind.SMOKER;
        }
    }

    private void scanAround(ClientWorld world, BlockPos center, int radius) {
        int r2 = radius * radius;
        for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
            for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) {
                    continue;
                }
                Chunk chunk = world.getChunk(cx, cz);
                if (!(chunk instanceof WorldChunk worldChunk)) {
                    continue;
                }
                for (BlockEntity be : new ArrayList<>(worldChunk.getBlockEntities().values())) {
                    if (!(be instanceof AbstractFurnaceBlockEntity) || be.getPos().getSquaredDistance(center) > r2) {
                        continue;
                    }
                    Kind kind = be instanceof BlastFurnaceBlockEntity ? Kind.BLAST
                            : be instanceof SmokerBlockEntity ? Kind.SMOKER : Kind.FURNACE;
                    this.furnaces.putIfAbsent(be.getPos().toImmutable(), kind);
                }
            }
        }
    }

    /** What a furnace of this kind may be loaded with, best first. */
    private List<Item> accepts(Kind kind) {
        List<Item> out = new ArrayList<>();
        switch (kind) {
            case BLAST -> out.addAll(ORES);
            case SMOKER -> out.addAll(FOOD);
            default -> {
                out.addAll(BLOCKS);
                if (!this.hasBlast) {
                    out.addAll(ORES);
                }
                if (!this.hasSmoker) {
                    out.addAll(FOOD);
                }
            }
        }
        return out;
    }

    /**
     * Give each furnace the input storage (plus inventory) has most of, a stack at most, and work
     * out the fuel for all of it.
     *
     * @return what to fetch from storage first
     */
    private Map<Item, Integer> plan(PlayerInventory inv) {
        Map<Item, Integer> available = new HashMap<>();
        Map<Item, Integer> wanted = new LinkedHashMap<>();
        int smelts = 0;
        // Specialised furnaces pick first so plain furnaces don't take their items.
        List<BlockPos> order = new ArrayList<>(this.furnaces.keySet());
        order.sort(Comparator.comparingInt(p -> this.furnaces.get(p) == Kind.FURNACE ? 1 : 0));
        for (BlockPos pos : order) {
            Item best = null;
            int bestCount = 0;
            for (Item item : accepts(this.furnaces.get(pos))) {
                int n = available.computeIfAbsent(item, i -> StorageManager.get().total(i) + inv.count(i));
                if (n > bestCount) {
                    best = item;
                    bestCount = n;
                }
            }
            if (best == null) {
                continue;
            }
            int amount = Math.min(best.getMaxCount(), bestCount);
            available.put(best, bestCount - amount);
            wanted.merge(best, amount, Integer::sum);
            this.planned.put(pos, best);
            this.handled.add(best);
            smelts += amount;
        }

        // Fuel, cheapest-to-carry first. Fuel already in the inventory counts.
        double left = smelts;
        for (Item fuel : fuelCandidates(inv)) {
            if (left <= 0) {
                break;
            }
            int have = StorageManager.get().total(fuel) + inv.count(fuel);
            if (have <= 0) {
                continue;
            }
            int take = Math.min(have, (int) Math.ceil(left / fuelValue(fuel)));
            wanted.merge(fuel, take, Integer::sum);
            this.handled.add(fuel);
            left -= take * fuelValue(fuel);
        }

        Map<Item, Integer> fetch = new LinkedHashMap<>();
        wanted.forEach((item, n) -> {
            int shortBy = Math.min(n - inv.count(item), StorageManager.get().total(item));
            if (shortBy > 0) {
                fetch.put(item, shortBy);
            }
        });
        return fetch;
    }

    /** Coal first, then planks and logs from whatever storage and the inventory hold. */
    private static List<Item> fuelCandidates(PlayerInventory inv) {
        List<Item> out = new ArrayList<>(List.of(Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK));
        Set<Item> known = new LinkedHashSet<>(StorageManager.get().totals().keySet());
        for (int i = 0; i < 36; i++) {
            known.add(inv.getStack(i).getItem());
        }
        for (Item item : known) {
            if (new ItemStack(item).isIn(ItemTags.PLANKS)) {
                out.add(item);
            }
        }
        for (Item item : known) {
            if (new ItemStack(item).isIn(ItemTags.LOGS_THAT_BURN)) {
                out.add(item);
            }
        }
        return out;
    }

    /** Items smelted per fuel item; 0 if we don't use it as fuel. */
    private static double fuelValue(Item item) {
        if (item == Items.COAL || item == Items.CHARCOAL) {
            return 8;
        }
        if (item == Items.COAL_BLOCK) {
            return 80;
        }
        ItemStack stack = new ItemStack(item);
        if (stack.isIn(ItemTags.PLANKS) || stack.isIn(ItemTags.LOGS_THAT_BURN)) {
            return 1.5;
        }
        return 0;
    }

    private void planRoute(BlockPos start) {
        List<BlockPos> left = new ArrayList<>(this.furnaces.keySet());
        BlockPos from = start;
        while (!left.isEmpty()) {
            BlockPos origin = from;
            BlockPos next = left.stream().min(Comparator.comparingDouble(p -> p.getSquaredDistance(origin))).orElseThrow();
            left.remove(next);
            this.route.add(next);
            from = next;
        }
    }

    private static Map<Item, Integer> inventoryCounts(PlayerInventory inv) {
        Map<Item, Integer> out = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                out.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ one furnace

    /** Works an open furnace screen: one step per tick (collect, load input, load fuel, tidy up). */
    private final class FurnaceWork {
        private final Kind kind;
        @Nullable
        private final Item planned;
        private int step;

        FurnaceWork(Kind kind, @Nullable Item planned) {
            this.kind = kind;
            this.planned = planned;
        }

        boolean run(MinecraftClient client, ScreenHandler handler) {
            PlayerEntity player = client.player;
            if (player == null || client.interactionManager == null || !(handler instanceof AbstractFurnaceScreenHandler)) {
                return true;
            }
            switch (this.step++) {
                case 0 -> collect(client, handler, player);
                case 1 -> loadInput(client, handler, player);
                case 2 -> loadFuel(client, handler, player);
                default -> {
                    tidyCursor(client, handler, player);
                    return true;
                }
            }
            return false;
        }

        private void collect(MinecraftClient client, ScreenHandler handler, PlayerEntity player) {
            Slot out = handler.getSlot(SLOT_OUTPUT);
            if (!out.hasStack()) {
                return;
            }
            Item item = out.getStack().getItem();
            int before = player.getInventory().count(item);
            client.interactionManager.clickSlot(handler.syncId, SLOT_OUTPUT, 0, SlotActionType.QUICK_MOVE, player);
            int got = player.getInventory().count(item) - before;
            if (got > 0) {
                collected.merge(item, got, Integer::sum);
            }
            if (out.hasStack()) {
                Messages.warn("Inventory full, some furnace output left behind");
            }
        }

        private void loadInput(MinecraftClient client, ScreenHandler handler, PlayerEntity player) {
            ItemStack current = handler.getSlot(SLOT_INPUT).getStack();
            Item item = null;
            if (!current.isEmpty()) {
                // Top up what's already smelting, if we carry more of it.
                if (current.getCount() < current.getMaxCount() && accepts(this.kind).contains(current.getItem())) {
                    item = current.getItem();
                }
            } else if (this.planned != null && player.getInventory().count(this.planned) > 0) {
                item = this.planned;
            } else {
                for (Item candidate : accepts(this.kind)) {
                    if (player.getInventory().count(candidate) > 0) {
                        item = candidate;
                        break;
                    }
                }
            }
            if (item == null) {
                return;
            }
            Slot from = carrying(handler, item);
            if (from == null) {
                return;
            }
            handled.add(item);
            // Pick the stack up, drop it on the input slot, put any remainder back.
            client.interactionManager.clickSlot(handler.syncId, from.id, 0, SlotActionType.PICKUP, player);
            client.interactionManager.clickSlot(handler.syncId, SLOT_INPUT, 0, SlotActionType.PICKUP, player);
            if (!handler.getCursorStack().isEmpty()) {
                client.interactionManager.clickSlot(handler.syncId, from.id, 0, SlotActionType.PICKUP, player);
            }
            loaded++;
        }

        private void loadFuel(MinecraftClient client, ScreenHandler handler, PlayerEntity player) {
            int toSmelt = handler.getSlot(SLOT_INPUT).getStack().getCount();
            if (toSmelt <= 0) {
                return;
            }
            ItemStack current = handler.getSlot(SLOT_FUEL).getStack();
            double covered = current.isEmpty() ? 0 : fuelValue(current.getItem()) * current.getCount();
            Item fuel = null;
            if (!current.isEmpty()) {
                if (fuelValue(current.getItem()) > 0) {
                    fuel = current.getItem();
                }
            } else {
                for (Item candidate : fuelCandidates(player.getInventory())) {
                    if (player.getInventory().count(candidate) > 0) {
                        fuel = candidate;
                        break;
                    }
                }
            }
            if (fuel == null || covered >= toSmelt) {
                return;
            }
            int need = (int) Math.ceil((toSmelt - covered) / fuelValue(fuel));
            Slot from = carrying(handler, fuel);
            if (need <= 0 || from == null) {
                return;
            }
            handled.add(fuel);
            client.interactionManager.clickSlot(handler.syncId, from.id, 0, SlotActionType.PICKUP, player);
            int onCursor = handler.getCursorStack().getCount();
            if (onCursor <= need || need > MAX_RIGHT_CLICKS) {
                // Whole stack (a little extra fuel just stays in the slot for next time).
                client.interactionManager.clickSlot(handler.syncId, SLOT_FUEL, 0, SlotActionType.PICKUP, player);
            } else {
                // Right-click drops one item at a time.
                for (int i = 0; i < need; i++) {
                    client.interactionManager.clickSlot(handler.syncId, SLOT_FUEL, 1, SlotActionType.PICKUP, player);
                }
            }
            if (!handler.getCursorStack().isEmpty()) {
                client.interactionManager.clickSlot(handler.syncId, from.id, 0, SlotActionType.PICKUP, player);
            }
        }

        /** Never close the screen with something on the cursor: put it in any free player slot. */
        private void tidyCursor(MinecraftClient client, ScreenHandler handler, PlayerEntity player) {
            if (handler.getCursorStack().isEmpty()) {
                return;
            }
            for (Slot slot : handler.slots) {
                if (slot.inventory instanceof PlayerInventory && !slot.hasStack()) {
                    client.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, player);
                    return;
                }
            }
        }

        /** The biggest stack of {@code item} in the player part of the screen. */
        @Nullable
        private Slot carrying(ScreenHandler handler, Item item) {
            Slot best = null;
            for (Slot slot : handler.slots) {
                if (slot.inventory instanceof PlayerInventory && slot.getStack().isOf(item)
                        && (best == null || slot.getStack().getCount() > best.getStack().getCount())) {
                    best = slot;
                }
            }
            return best;
        }
    }

    @Override
    public void cancel(MinecraftClient client) {
        if (this.visit != null) {
            this.visit.cancel(client);
        }
    }
}
