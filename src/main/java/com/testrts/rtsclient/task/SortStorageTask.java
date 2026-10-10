package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reorganises linked chests, barrels and shulker boxes so each item type sits together.
 *
 * <p>Planning works on the storage index: every item type gets a slot quota in one container (or
 * several, for big amounts), largest totals first. A container mostly filled by one item keeps
 * it, and spill-over goes next to where the item already is. Then containers are visited one at a
 * time: misplaced stacks are taken out (as much as fits), and carried stacks are put into the
 * containers whose quota they fill. Every visit refreshes the index. A container that yields no
 * progress is skipped from then on, and the number of visits is capped, so it can't loop forever.
 */
public final class SortStorageTask implements RtsTask {
    private static final int CLICKS_PER_TICK = 6;
    private static final int MAX_VISITS = 120;

    private enum Phase { INDEX, PLAN, MOVE, FINISH, DONE }

    private Phase phase = Phase.INDEX;
    /** Per container: item -> how many of it belong there. */
    private final Map<BlockPos, Map<Item, Integer>> quota = new LinkedHashMap<>();
    /** Items taken out of storage and not yet put back. */
    private final Map<Item, Integer> carried = new HashMap<>();
    private final Set<BlockPos> skipped = new HashSet<>();
    private Map<Item, Integer> initial = Map.of();
    private ContainerVisitTask visit;
    private Map<Item, Integer> before;
    private BlockPos visiting;
    private int visits;
    private int moved;
    private String detail = "indexing";

    public SortStorageTask() {
    }

    @Override
    public String label() {
        return "Sort storage";
    }

    @Override
    public String detail() {
        if (this.visit != null) {
            return "visit " + this.visits + " - " + this.visit.detail();
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
            case INDEX -> {
                // Plans are only as good as the index: look into anything we haven't seen yet.
                this.phase = Phase.PLAN;
                if (!StorageManager.get().unindexed().isEmpty()) {
                    queue.pushFront(new IndexStorageTask(player.getBlockPos(), true));
                }
                return Status.RUNNING;
            }
            case PLAN -> {
                plan(client.world);
                if (this.quota.isEmpty()) {
                    Messages.warn("Nothing to sort - link and index some chests first");
                    return Status.DONE;
                }
                if (player.getInventory().getEmptySlot() < 0) {
                    Messages.warn("Sorting needs some free inventory space");
                    return Status.DONE;
                }
                this.initial = inventoryCounts(player.getInventory());
                this.phase = Phase.MOVE;
                this.detail = "planning moves";
                return Status.RUNNING;
            }
            case MOVE -> {
                return move(client, queue);
            }
            case FINISH -> {
                this.phase = Phase.DONE;
                this.carried.values().removeIf(n -> n <= 0);
                if (!this.carried.isEmpty()) {
                    // Couldn't place everything we picked up: put it anywhere rather than keep it.
                    Messages.warn("Sorting left " + MaterialPlanner.describe(this.carried, 3) + " without a place, storing it anywhere");
                    Set<Item> left = new HashSet<>(this.carried.keySet());
                    queue.pushFront(new StoreItemsTask("Store leftovers", s -> left.contains(s.getItem()), this.initial, true));
                }
                return Status.RUNNING;
            }
            default -> {
                return Status.DONE;
            }
        }
    }

    // ------------------------------------------------------------------ moving

    private Status move(MinecraftClient client, TaskQueue queue) {
        PlayerEntity player = client.player;
        if (this.visit != null) {
            if (this.visit.tick(client, queue) == Status.RUNNING) {
                return Status.RUNNING;
            }
            this.visit = null;
            if (!settle(player.getInventory())) {
                this.skipped.add(this.visiting);
            }
        }
        if (this.visits >= MAX_VISITS) {
            Messages.warn("Sorting stopped after " + this.visits + " container visits");
            this.phase = Phase.FINISH;
            return Status.RUNNING;
        }
        BlockPos next = nextContainer(player);
        if (next == null) {
            if (this.carried.isEmpty()) {
                Messages.success("Storage sorted (" + this.moved / 2 + " items moved, " + this.visits + " visits)");
            }
            this.phase = Phase.FINISH;
            return Status.RUNNING;
        }
        this.visits++;
        this.visiting = next;
        this.before = inventoryCounts(player.getInventory());
        Visit work = new Visit(next);
        this.visit = new ContainerVisitTask(next, "Sort", work::run);
        return Status.RUNNING;
    }

    /** Book what the last visit moved into/out of the inventory. @return true if anything moved. */
    private boolean settle(PlayerInventory inv) {
        Map<Item, Integer> after = inventoryCounts(inv);
        boolean progress = false;
        Set<Item> all = new HashSet<>(after.keySet());
        all.addAll(this.before.keySet());
        for (Item item : all) {
            int d = after.getOrDefault(item, 0) - this.before.getOrDefault(item, 0);
            if (d != 0) {
                progress = true;
                this.moved += Math.abs(d);
                int now = this.carried.getOrDefault(item, 0) + d;
                if (now > 0) {
                    this.carried.put(item, now);
                } else {
                    this.carried.remove(item);
                }
            }
        }
        this.before = after;
        return progress;
    }

    /** Nearest container where we can deliver what we carry or take out something misplaced. */
    private BlockPos nextContainer(PlayerEntity player) {
        boolean room = player.getInventory().getEmptySlot() >= 0;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : this.quota.keySet()) {
            if (this.skipped.contains(pos)) {
                continue;
            }
            boolean useful = false;
            for (Item item : this.carried.keySet()) {
                if (deficit(pos, item) > 0) {
                    useful = true;
                    break;
                }
            }
            if (!useful && room) {
                StorageManager.LinkedContainer c = StorageManager.get().container(pos);
                if (c != null) {
                    for (Item item : c.items().keySet()) {
                        if (surplus(pos, item) > 0 && neededElsewhere(pos, item) > 0) {
                            useful = true;
                            break;
                        }
                    }
                }
            }
            if (!useful) {
                continue;
            }
            double d = pos.getSquaredDistance(player.getBlockPos());
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    private int count(BlockPos pos, Item item) {
        StorageManager.LinkedContainer c = StorageManager.get().container(pos);
        return c == null ? 0 : c.count(item);
    }

    private int quota(BlockPos pos, Item item) {
        Map<Item, Integer> q = this.quota.get(pos);
        return q == null ? 0 : q.getOrDefault(item, 0);
    }

    private int surplus(BlockPos pos, Item item) {
        return count(pos, item) - quota(pos, item);
    }

    private int deficit(BlockPos pos, Item item) {
        return quota(pos, item) - count(pos, item);
    }

    /** Room left for {@code item} in its other target containers, minus what we already carry. */
    private int neededElsewhere(BlockPos except, Item item) {
        int need = 0;
        for (BlockPos pos : this.quota.keySet()) {
            if (!pos.equals(except) && !this.skipped.contains(pos)) {
                need += Math.max(0, deficit(pos, item));
            }
        }
        return need - this.carried.getOrDefault(item, 0);
    }

    /** One container visit: take misplaced stacks out first (frees room), then put carried ones in. */
    private final class Visit {
        private final BlockPos pos;
        private final Map<Item, Integer> takeLeft = new HashMap<>();
        private final Map<Item, Integer> putLeft = new HashMap<>();
        private final Set<Integer> clicked = new HashSet<>();
        private boolean taking = true;

        Visit(BlockPos pos) {
            this.pos = pos;
            StorageManager.LinkedContainer c = StorageManager.get().container(pos);
            if (c != null) {
                for (Item item : c.items().keySet()) {
                    int s = Math.min(surplus(pos, item), neededElsewhere(pos, item));
                    if (s > 0) {
                        this.takeLeft.put(item, s);
                    }
                }
            }
            carried.forEach((item, n) -> {
                int d = Math.min(n, deficit(pos, item));
                if (d > 0) {
                    this.putLeft.put(item, d);
                }
            });
        }

        boolean run(MinecraftClient client, ScreenHandler handler) {
            PlayerEntity player = client.player;
            if (player == null || client.interactionManager == null) {
                return true;
            }
            int clicks = 0;
            if (this.taking) {
                for (Slot slot : handler.slots) {
                    if (slot.inventory instanceof PlayerInventory || !slot.hasStack() || this.clicked.contains(slot.id)) {
                        continue;
                    }
                    ItemStack stack = slot.getStack();
                    int left = this.takeLeft.getOrDefault(stack.getItem(), 0);
                    // Whole stacks only; a partial surplus stays put rather than over-taking.
                    if (left <= 0 || stack.getCount() > left) {
                        continue;
                    }
                    if (player.getInventory().getEmptySlot() < 0 && player.getInventory().getOccupiedSlotWithRoomForStack(stack) < 0) {
                        break;
                    }
                    this.clicked.add(slot.id);
                    this.takeLeft.put(stack.getItem(), left - stack.getCount());
                    client.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player);
                    if (++clicks >= CLICKS_PER_TICK) {
                        return false;
                    }
                }
                this.taking = false;
                this.clicked.clear();
            }
            // Main inventory before the hotbar, so the player's working set is touched last.
            for (int pass = 0; pass < 2; pass++) {
                int from = pass == 0 ? 9 : 0;
                int to = pass == 0 ? 36 : 9;
                for (int i = from; i < to; i++) {
                    Slot slot = Jobs.playerSlot(handler, i);
                    if (slot == null || !slot.hasStack() || this.clicked.contains(slot.id)) {
                        continue;
                    }
                    ItemStack stack = slot.getStack();
                    int left = this.putLeft.getOrDefault(stack.getItem(), 0);
                    if (left <= 0) {
                        continue;
                    }
                    this.clicked.add(slot.id);
                    this.putLeft.put(stack.getItem(), left - stack.getCount());
                    client.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player);
                    if (++clicks >= CLICKS_PER_TICK) {
                        return false;
                    }
                }
            }
            return true;
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

    // ------------------------------------------------------------------ planning

    private void plan(ClientWorld world) {
        this.quota.clear();
        Map<BlockPos, Integer> budget = new LinkedHashMap<>();
        List<StorageManager.LinkedContainer> containers = new ArrayList<>();
        for (StorageManager.LinkedContainer c : StorageManager.get().linked()) {
            int slots = c.indexed() ? capacity(world, c.pos()) : 0;
            if (slots > 0) {
                containers.add(c);
                budget.put(c.pos(), slots);
                this.quota.put(c.pos(), new LinkedHashMap<>());
            }
        }
        Map<Item, Integer> totals = new HashMap<>();
        for (StorageManager.LinkedContainer c : containers) {
            c.items().forEach((item, n) -> totals.merge(item, n, Integer::sum));
        }
        Map<Item, Integer> itemsLeft = new HashMap<>(totals);
        Map<Item, BlockPos> home = new HashMap<>();

        // 1) A container at least half full of one item keeps that item.
        for (StorageManager.LinkedContainer c : containers) {
            Item dominant = null;
            int most = 0;
            for (Map.Entry<Item, Integer> e : c.items().entrySet()) {
                if (e.getValue() > most) {
                    most = e.getValue();
                    dominant = e.getKey();
                }
            }
            if (dominant != null && stacks(dominant, most) * 2 >= budget.get(c.pos()) && allowed(world, c.pos(), dominant)) {
                assign(c.pos(), dominant, budget, itemsLeft);
                home.putIfAbsent(dominant, c.pos());
            }
        }

        // 2) Everything else, largest totals first, next to where the item already is.
        List<Item> order = new ArrayList<>(totals.keySet());
        order.sort(Comparator.comparingInt((Item i) -> -totals.get(i)));
        for (Item item : order) {
            while (itemsLeft.getOrDefault(item, 0) > 0) {
                BlockPos anchor = home.get(item);
                StorageManager.LinkedContainer pick = null;
                for (StorageManager.LinkedContainer c : containers) {
                    if (budget.get(c.pos()) <= 0 || !allowed(world, c.pos(), item)) {
                        continue;
                    }
                    if (pick == null || better(c, pick, item, anchor)) {
                        pick = c;
                    }
                }
                if (pick == null) {
                    break;
                }
                assign(pick.pos(), item, budget, itemsLeft);
                home.putIfAbsent(item, pick.pos());
            }
        }
    }

    /** Container {@code a} is a better place for {@code item} than {@code b}. */
    private boolean better(StorageManager.LinkedContainer a, StorageManager.LinkedContainer b, Item item, BlockPos anchor) {
        // Where it already is, then containers with fewer other assigned items, then close to its home.
        if (a.count(item) != b.count(item)) {
            return a.count(item) > b.count(item);
        }
        int ka = this.quota.get(a.pos()).size();
        int kb = this.quota.get(b.pos()).size();
        if (ka != kb) {
            return ka < kb;
        }
        if (anchor != null) {
            return a.pos().getSquaredDistance(anchor) < b.pos().getSquaredDistance(anchor);
        }
        return false;
    }

    private void assign(BlockPos pos, Item item, Map<BlockPos, Integer> budget, Map<Item, Integer> itemsLeft) {
        int left = itemsLeft.getOrDefault(item, 0);
        int free = budget.get(pos);
        if (left <= 0 || free <= 0) {
            return;
        }
        int take = Math.min(stacks(item, left), free);
        int amount = Math.min(left, take * item.getMaxCount());
        budget.put(pos, free - take);
        itemsLeft.put(item, left - amount);
        this.quota.get(pos).merge(item, amount, Integer::sum);
    }

    private static int stacks(Item item, int count) {
        int max = Math.max(1, item.getMaxCount());
        return (count + max - 1) / max;
    }

    /** Shulker boxes can't go into shulker boxes. */
    private static boolean allowed(ClientWorld world, BlockPos pos, Item item) {
        return !(world.getBlockEntity(pos) instanceof ShulkerBoxBlockEntity)
                || !(Block.getBlockFromItem(item) instanceof ShulkerBoxBlock);
    }

    /** Slots of a chest (54 when double), barrel or shulker box; 0 for anything else or unloaded. */
    private static int capacity(ClientWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) {
            return 0;
        }
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof ChestBlockEntity) {
            BlockState state = world.getBlockState(pos);
            boolean dbl = state.getBlock() instanceof ChestBlock && state.contains(ChestBlock.CHEST_TYPE)
                    && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE;
            return dbl ? 54 : 27;
        }
        if (be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity) {
            return ((Inventory) be).size();
        }
        return 0;
    }

    @Override
    public void suspend(MinecraftClient client) {
        if (this.visit != null) {
            this.visit.cancel(client);
            this.visit = null;
            if (client.player != null && this.before != null) {
                settle(client.player.getInventory());
            }
        }
    }

    @Override
    public void cancel(MinecraftClient client) {
        if (this.visit != null) {
            this.visit.cancel(client);
        }
    }
}
