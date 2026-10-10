package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Harvests ripe crops in an area (wheat, carrots, potatoes, beetroots, nether wart, cocoa), picks
 * up the drops, replants each spot with its seed, and stores the produce in linked storage while
 * keeping a stack of every seed for next time. Crops break instantly, so they are punched directly
 * as the player walks past instead of going through the build engine.
 */
public final class HarvestTask implements RtsTask {
    private static final int PLANT_WAIT_TICKS = 3;
    private static final int MAX_TRIES = 2;
    private static final int MAX_BREAK_TICKS = 60;
    /** Seeds (and seed-like produce) to keep in the inventory when storing the harvest. */
    private static final int KEEP_SEEDS = 64;

    private enum Phase { SCAN, BREAK, COLLECT, FETCH_SEEDS, REPLANT, STORE, DONE }

    /** A crop spot: where it grows, and what is clicked to replant it. */
    private record Spot(BlockPos pos, Item seed, BlockPos clickOn, Direction face) {
    }

    /** Everything a harvest can drop; all of it may go to storage (minus the kept seeds). */
    private static final Set<Item> PRODUCE = Set.of(Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO,
            Items.POISONOUS_POTATO, Items.BEETROOT, Items.BEETROOT_SEEDS, Items.NETHER_WART, Items.COCOA_BEANS);
    private static final Set<Item> SEEDS = Set.of(Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO,
            Items.BEETROOT_SEEDS, Items.NETHER_WART, Items.COCOA_BEANS);

    private final BlockPos min;
    private final BlockPos max;
    private Phase phase = Phase.SCAN;
    private final List<Spot> toBreak = new ArrayList<>();
    private final List<Spot> toPlant = new ArrayList<>();
    private Spot target;
    private WalkPhase walk;
    private int wait;
    private int tries;
    private int breakTicks;
    private int found;
    private int harvested;
    private int replanted;
    private final Set<Item> fetched = new HashSet<>();
    private String detail = "looking for ripe crops";

    public HarvestTask(BlockPos a, BlockPos b) {
        this.min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }

    @Override
    public String label() {
        return "Harvest crops";
    }

    @Override
    public String detail() {
        return switch (this.phase) {
            case BREAK -> "harvesting " + this.harvested + " / " + this.found;
            case REPLANT -> "replanting, " + this.toPlant.size() + " left";
            default -> this.detail;
        };
    }

    @Override
    public Status tick(MinecraftClient client, TaskQueue queue) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return Status.FAILED;
        }
        switch (this.phase) {
            case SCAN -> {
                scan(client.world);
                this.found = this.toBreak.size();
                if (this.toBreak.isEmpty()) {
                    Messages.info("No ripe crops in the selected area");
                    return Status.DONE;
                }
                this.phase = Phase.BREAK;
                return Status.RUNNING;
            }
            case BREAK -> {
                return breakNext(client);
            }
            case COLLECT -> {
                this.detail = "collecting drops";
                this.phase = Phase.FETCH_SEEDS;
                queue.pushFront(new CollectDropsTask(area()));
                return Status.RUNNING;
            }
            case FETCH_SEEDS -> {
                Map<Item, Integer> need = new LinkedHashMap<>();
                for (Spot s : this.toPlant) {
                    need.merge(s.seed(), 1, Integer::sum);
                }
                Map<Item, Integer> fetch = new LinkedHashMap<>();
                need.forEach((item, n) -> {
                    int shortBy = n - player.getInventory().count(item);
                    int stored = StorageManager.get().total(item);
                    if (shortBy > 0 && stored > 0 && this.fetched.add(item)) {
                        fetch.put(item, Math.min(shortBy, stored));
                    }
                });
                this.phase = Phase.REPLANT;
                if (!fetch.isEmpty()) {
                    this.detail = "fetching seeds";
                    queue.pushFront(new FetchTask(fetch));
                }
                return Status.RUNNING;
            }
            case REPLANT -> {
                return replantNext(client);
            }
            case STORE -> {
                this.phase = Phase.DONE;
                Messages.success("Harvested " + this.harvested + " crop(s), replanted " + this.replanted);
                if (StorageManager.get().linked().isEmpty()) {
                    Messages.warn("No linked storage - the harvest stays in your inventory");
                    return Status.DONE;
                }
                Map<Item, Integer> keep = new HashMap<>();
                SEEDS.forEach(seed -> keep.put(seed, KEEP_SEEDS));
                queue.pushFront(new StoreItemsTask("Store harvest", s -> PRODUCE.contains(s.getItem()), keep, true));
                return Status.RUNNING;
            }
            default -> {
                return Status.DONE;
            }
        }
    }

    // ------------------------------------------------------------------ breaking

    private Status breakNext(MinecraftClient client) {
        ClientWorld world = client.world;
        if (this.wait > 0) {
            this.wait--;
            return Status.RUNNING;
        }
        if (this.target == null) {
            if (this.toBreak.isEmpty()) {
                this.phase = Phase.COLLECT;
                return Status.RUNNING;
            }
            this.target = nearest(this.toBreak, client.player.getBlockPos());
            this.toBreak.remove(this.target);
            this.walk = new WalkPhase(this.target.pos());
            this.breakTicks = 0;
        }
        Spot spot = this.target;
        BlockState state = world.getBlockState(spot.pos());
        if (ripeSeed(state) == null) {
            // Broken (by our last punch or by something else): replant right away if we can.
            if (state.isAir()) {
                this.harvested++;
                // Replant now if the seed is at hand; the replant phase checks it took (or retries).
                tryPlant(client, spot);
                this.toPlant.add(spot);
            }
            this.target = null;
            return Status.RUNNING;
        }
        if (this.breakTicks > MAX_BREAK_TICKS) {
            client.interactionManager.cancelBlockBreaking();
            this.target = null;
            return Status.RUNNING;
        }
        if (this.breakTicks == 0) {
            WalkPhase.Result r = this.walk.tick(client);
            if (r == WalkPhase.Result.WALKING) {
                return Status.RUNNING;
            }
            if (r == WalkPhase.Result.UNREACHABLE) {
                Messages.warn("Can't reach the crop at " + spot.pos().toShortString());
                this.target = null;
                return Status.RUNNING;
            }
        }
        // Crops break on the first hit; cocoa takes a few ticks of mining.
        Reach.lookAt(client.player, Vec3d.ofCenter(spot.pos()));
        if (this.breakTicks++ == 0) {
            client.interactionManager.attackBlock(spot.pos(), Direction.UP);
        } else {
            client.interactionManager.updateBlockBreakingProgress(spot.pos(), Direction.UP);
        }
        client.player.swingHand(Hand.MAIN_HAND);
        return Status.RUNNING;
    }

    // ------------------------------------------------------------------ replanting

    private Status replantNext(MinecraftClient client) {
        if (this.wait > 0) {
            this.wait--;
            return Status.RUNNING;
        }
        if (this.target == null) {
            if (this.toPlant.isEmpty()) {
                this.phase = Phase.STORE;
                return Status.RUNNING;
            }
            this.target = nearest(this.toPlant, client.player.getBlockPos());
            this.walk = new WalkPhase(this.target.clickOn());
            this.tries = 0;
        }
        Spot spot = this.target;
        if (!canPlant(client.world, spot) || this.tries >= MAX_TRIES) {
            if (isPlanted(client.world, spot)) {
                this.replanted++;
            }
            this.toPlant.remove(spot);
            this.target = null;
            return Status.RUNNING;
        }
        if (client.player.getInventory().count(spot.seed()) <= 0) {
            Messages.warn("No " + spot.seed().getName().getString() + " left to replant");
            this.toPlant.removeIf(s -> s.seed() == spot.seed());
            this.target = null;
            return Status.RUNNING;
        }
        WalkPhase.Result r = this.walk.tick(client);
        if (r == WalkPhase.Result.WALKING) {
            return Status.RUNNING;
        }
        if (r == WalkPhase.Result.UNREACHABLE) {
            Messages.warn("Can't reach " + spot.pos().toShortString() + " to replant");
            this.toPlant.remove(spot);
            this.target = null;
            return Status.RUNNING;
        }
        this.tries++;
        Jobs.useOn(client, spot.clickOn(), spot.face(), spot.seed());
        this.wait = PLANT_WAIT_TICKS;
        return Status.RUNNING;
    }

    /** Plant right after breaking when the seed is at hand and the spot is in reach. */
    private void tryPlant(MinecraftClient client, Spot spot) {
        if (client.player.getInventory().count(spot.seed()) > 0 && Reach.inReach(client, spot.clickOn())
                && canPlant(client.world, spot) && Jobs.useOn(client, spot.clickOn(), spot.face(), spot.seed())) {
            this.wait = PLANT_WAIT_TICKS;
        }
    }

    private static boolean canPlant(ClientWorld world, Spot spot) {
        if (!world.getBlockState(spot.pos()).isAir()) {
            return false;
        }
        BlockState support = world.getBlockState(spot.clickOn());
        if (spot.seed() == Items.NETHER_WART) {
            return support.isOf(Blocks.SOUL_SAND);
        }
        if (spot.seed() == Items.COCOA_BEANS) {
            return support.isOf(Blocks.JUNGLE_LOG) || support.isOf(Blocks.JUNGLE_WOOD)
                    || support.isOf(Blocks.STRIPPED_JUNGLE_LOG) || support.isOf(Blocks.STRIPPED_JUNGLE_WOOD);
        }
        return support.isOf(Blocks.FARMLAND);
    }

    private static boolean isPlanted(ClientWorld world, Spot spot) {
        Block block = world.getBlockState(spot.pos()).getBlock();
        return block == cropFor(spot.seed());
    }

    @Nullable
    private static Block cropFor(Item seed) {
        if (seed == Items.WHEAT_SEEDS) {
            return Blocks.WHEAT;
        } else if (seed == Items.CARROT) {
            return Blocks.CARROTS;
        } else if (seed == Items.POTATO) {
            return Blocks.POTATOES;
        } else if (seed == Items.BEETROOT_SEEDS) {
            return Blocks.BEETROOTS;
        } else if (seed == Items.NETHER_WART) {
            return Blocks.NETHER_WART;
        } else if (seed == Items.COCOA_BEANS) {
            return Blocks.COCOA;
        }
        return null;
    }

    // ------------------------------------------------------------------ scanning

    private void scan(ClientWorld world) {
        for (BlockPos p : BlockPos.iterate(this.min, this.max)) {
            if (!world.isChunkLoaded(p)) {
                continue;
            }
            BlockState state = world.getBlockState(p);
            Item seed = ripeSeed(state);
            if (seed == null) {
                continue;
            }
            BlockPos pos = p.toImmutable();
            if (state.isOf(Blocks.COCOA)) {
                // Cocoa hangs on the log it faces; replanting clicks that log's side.
                Direction facing = state.get(CocoaBlock.FACING);
                this.toBreak.add(new Spot(pos, seed, pos.offset(facing), facing.getOpposite()));
            } else {
                this.toBreak.add(new Spot(pos, seed, pos.down(), Direction.UP));
            }
        }
    }

    /** The item that replants this block if it is a ripe crop, else null. */
    @Nullable
    private static Item ripeSeed(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop && crop.isMature(state)) {
            if (block == Blocks.WHEAT) {
                return Items.WHEAT_SEEDS;
            } else if (block == Blocks.CARROTS) {
                return Items.CARROT;
            } else if (block == Blocks.POTATOES) {
                return Items.POTATO;
            } else if (block == Blocks.BEETROOTS) {
                return Items.BEETROOT_SEEDS;
            }
            return null;
        }
        if (block == Blocks.NETHER_WART && state.get(NetherWartBlock.AGE) >= 3) {
            return Items.NETHER_WART;
        }
        if (block == Blocks.COCOA && state.get(CocoaBlock.AGE) >= 2) {
            return Items.COCOA_BEANS;
        }
        return null;
    }

    private static Spot nearest(List<Spot> spots, BlockPos from) {
        return spots.stream().min(Comparator.comparingDouble(s -> s.pos().getSquaredDistance(from))).orElseThrow();
    }

    private Box area() {
        return new Box(this.min.getX(), this.min.getY(), this.min.getZ(), this.max.getX() + 1, this.max.getY() + 1, this.max.getZ() + 1);
    }

    @Override
    public void cancel(MinecraftClient client) {
        if (this.target != null && this.breakTicks > 0 && client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
        this.target = null;
    }
}
