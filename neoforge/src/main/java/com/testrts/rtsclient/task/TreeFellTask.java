package com.testrts.rtsclient.task;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fells every tree in an area, nearest first: all logs of the tree are cleared through the engine
 * (an inline {@link BuildTask}), drops are picked up, and optionally a sapling of the same kind is
 * planted where the trunk stood. Log blocks without natural leaves near their top (houses, fences
 * made of logs) are left alone.
 */
public final class TreeFellTask implements RtsTask {
    /** How far a tree may reach out of the selected area (branches, tall trunks). */
    private static final int SPILL_SIDE = 8;
    private static final int SPILL_UP = 32;
    private static final int SPILL_DOWN = 4;
    private static final int LEAF_RADIUS = 3;
    /** Bigger log clusters are almost certainly a build, not a tree. */
    private static final int MAX_TREE_LOGS = 600;
    private static final int PLANT_WAIT_TICKS = 4;
    private static final int MAX_PLANT_TRIES = 2;

    private enum Phase { SCAN, NEXT, CHOP, FETCH_SAPLINGS, PLANT, DONE }

    /** One tree: its logs, the trunk positions to replant, and the sapling to use. */
    private record Tree(Set<BlockPos> logs, List<BlockPos> bases, @Nullable Item sapling, AABB bounds) {
    }

    private final BlockPos min;
    private final BlockPos max;
    private final boolean replant;
    private final List<Tree> trees = new ArrayList<>();
    private Phase phase = Phase.SCAN;
    private Tree current;
    private BuildTask chop;
    private final Deque<BlockPos> toPlant = new ArrayDeque<>();
    private WalkPhase walk;
    private int plantWait;
    private int plantTries;
    private int felled;
    private int logsCut;
    private int planted;
    private int found;
    private String detail = "looking for trees";

    public BlockPos min() {
        return this.min;
    }

    public BlockPos max() {
        return this.max;
    }

    public boolean replant() {
        return this.replant;
    }

    public TreeFellTask(BlockPos a, BlockPos b, boolean replant) {
        this.min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.replant = replant;
    }

    @Override
    public String label() {
        return this.replant ? "Fell trees & replant" : "Fell trees";
    }

    @Override
    public String detail() {
        String head = this.found > 0 ? "tree " + Math.min(this.felled + 1, this.found) + "/" + this.found + " - " : "";
        if (this.phase == Phase.CHOP && this.chop != null) {
            return head + "chopping, " + this.chop.detail();
        }
        return head + this.detail;
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        if (client.player == null || client.level == null) {
            return Status.FAILED;
        }
        switch (this.phase) {
            case SCAN -> {
                scan(client.level);
                this.found = this.trees.size();
                if (this.trees.isEmpty()) {
                    Messages.warn("No trees in the selected area");
                    return Status.DONE;
                }
                Messages.info("Felling " + this.trees.size() + " tree(s)");
                this.phase = Phase.NEXT;
                return Status.RUNNING;
            }
            case NEXT -> {
                if (this.trees.isEmpty()) {
                    this.phase = Phase.DONE;
                    Messages.success("Felled " + this.felled + " tree(s), " + this.logsCut + " logs"
                            + (this.replant ? ", replanted " + this.planted + " sapling(s)" : ""));
                    return Status.DONE;
                }
                BlockPos at = client.player.blockPosition();
                this.current = this.trees.stream()
                        .min(Comparator.comparingDouble(t -> t.bases().isEmpty()
                                ? t.logs().iterator().next().distSqr(at)
                                : t.bases().get(0).distSqr(at)))
                        .orElseThrow();
                this.trees.remove(this.current);
                Map<BlockPos, BlockState> order = new LinkedHashMap<>();
                BlockState air = Blocks.AIR.defaultBlockState();
                for (BlockPos p : this.current.logs()) {
                    if (client.level.getBlockState(p).is(BlockTags.LOGS)) {
                        order.put(p, air);
                    }
                }
                if (order.isEmpty()) {
                    // Already gone (cut by hand meanwhile); still replant.
                    afterChop(queue);
                    return Status.RUNNING;
                }
                this.chop = new BuildTask("Fell tree (" + order.size() + " logs)", order, true);
                this.phase = Phase.CHOP;
                return Status.RUNNING;
            }
            case CHOP -> {
                if (this.chop == null) {
                    this.phase = Phase.NEXT;
                    return Status.RUNNING;
                }
                if (this.chop.tick(client, queue) == Status.RUNNING) {
                    return Status.RUNNING;
                }
                this.logsCut += this.chop.blocks().size() - MaterialPlanner.remaining(client.level, this.chop.blocks());
                this.chop = null;
                afterChop(queue);
                return Status.RUNNING;
            }
            case FETCH_SAPLINGS -> {
                Item sapling = this.current.sapling();
                int have = client.player.getInventory().countItem(sapling);
                int stored = StorageManager.get().total(sapling);
                if (have < this.toPlant.size() && stored > 0) {
                    this.detail = "fetching saplings";
                    // Fetch a few extra so the next trees of this kind don't need another trip.
                    queue.pushFront(new FetchTask(Map.of(sapling, Math.min(stored, Math.max(this.toPlant.size(), 16)))));
                }
                this.phase = Phase.PLANT;
                return Status.RUNNING;
            }
            case PLANT -> {
                return plant(client);
            }
            default -> {
                return Status.DONE;
            }
        }
    }

    private void afterChop(TaskQueue queue) {
        this.felled++;
        if (!RtsConfig.get().collectDrops) {
            // The clear order only collects drops when the setting is on; trees always want it.
            queue.pushFront(new CollectDropsTask(this.current.bounds()));
        }
        this.toPlant.clear();
        if (this.replant && this.current.sapling() != null) {
            this.toPlant.addAll(this.current.bases());
        }
        this.walk = null;
        this.plantTries = 0;
        this.phase = this.toPlant.isEmpty() ? Phase.NEXT : Phase.FETCH_SAPLINGS;
    }

    private Status plant(Minecraft client) {
        ClientLevel world = client.level;
        if (this.plantWait > 0) {
            this.plantWait--;
            return Status.RUNNING;
        }
        BlockPos spot = this.toPlant.peekFirst();
        if (spot == null) {
            this.phase = Phase.NEXT;
            return Status.RUNNING;
        }
        Item sapling = this.current.sapling();
        BlockPos ground = spot.below();
        BlockState here = world.getBlockState(spot);
        if (!here.isAir() || !world.getBlockState(ground).is(BlockTags.DIRT) || this.plantTries >= MAX_PLANT_TRIES) {
            // Planted by the last click, taken by something else, or not plantable: move on.
            if (here.is(Block.byItem(sapling))) {
                this.planted++;
            }
            nextSpot();
            return Status.RUNNING;
        }
        if (client.player.getInventory().countItem(sapling) <= 0) {
            Messages.warn("Out of " + sapling.getDescription().getString() + ", can't replant");
            this.toPlant.clear();
            this.phase = Phase.NEXT;
            return Status.RUNNING;
        }
        if (this.walk == null) {
            this.walk = new WalkPhase(ground);
        }
        WalkPhase.Result r = this.walk.tick(client);
        if (r == WalkPhase.Result.WALKING) {
            this.detail = "walking to plant";
            return Status.RUNNING;
        }
        if (r == WalkPhase.Result.UNREACHABLE) {
            Messages.warn("Can't reach " + spot.toShortString() + " to replant");
            nextSpot();
            return Status.RUNNING;
        }
        this.detail = "planting " + sapling.getDescription().getString();
        this.plantTries++;
        if (!Jobs.useOn(client, ground, Direction.UP, sapling)) {
            nextSpot();
            return Status.RUNNING;
        }
        // Give the server a moment; the next pass sees the sapling (and counts it) or tries again.
        this.plantWait = PLANT_WAIT_TICKS;
        return Status.RUNNING;
    }

    private void nextSpot() {
        this.toPlant.pollFirst();
        this.walk = null;
        this.plantTries = 0;
    }

    // ------------------------------------------------------------------ finding trees

    private void scan(ClientLevel world) {
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(this.min, this.max)) {
            if (seen.contains(p) || !world.isLoaded(p) || !world.getBlockState(p).is(BlockTags.LOGS)) {
                continue;
            }
            Set<BlockPos> logs = connectedLogs(world, p.immutable());
            seen.addAll(logs);
            if (logs.size() > MAX_TREE_LOGS) {
                continue;
            }
            Tree tree = asTree(world, logs);
            if (tree != null) {
                this.trees.add(tree);
            }
        }
    }

    /** Logs touching each other (faces, edges or corners), allowed to spill a bit out of the area. */
    private Set<BlockPos> connectedLogs(ClientLevel world, BlockPos start) {
        Set<BlockPos> out = new HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        open.add(start);
        out.add(start);
        while (!open.isEmpty() && out.size() <= MAX_TREE_LOGS) {
            BlockPos p = open.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.offset(dx, dy, dz);
                        if (out.contains(n) || !inSpill(n) || !world.getBlockState(n).is(BlockTags.LOGS)) {
                            continue;
                        }
                        out.add(n);
                        open.add(n);
                    }
                }
            }
        }
        return out;
    }

    private boolean inSpill(BlockPos p) {
        return p.getX() >= this.min.getX() - SPILL_SIDE && p.getX() <= this.max.getX() + SPILL_SIDE
                && p.getZ() >= this.min.getZ() - SPILL_SIDE && p.getZ() <= this.max.getZ() + SPILL_SIDE
                && p.getY() >= this.min.getY() - SPILL_DOWN && p.getY() <= this.max.getY() + SPILL_UP;
    }

    /** A tree if natural leaves sit near its top logs; null for log builds. */
    @Nullable
    private static Tree asTree(ClientLevel world, Set<BlockPos> logs) {
        int top = Integer.MIN_VALUE;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : logs) {
            top = Math.max(top, p.getY());
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxZ = Math.max(maxZ, p.getZ());
        }
        BlockState leaves = null;
        for (BlockPos p : logs) {
            if (p.getY() < top - 1) {
                continue;
            }
            leaves = naturalLeavesNear(world, p);
            if (leaves != null) {
                break;
            }
        }
        if (leaves == null) {
            return null;
        }
        // Trunk: logs standing on something that isn't a log; replant where that's soil.
        List<BlockPos> bases = new ArrayList<>();
        BlockState baseLog = null;
        for (BlockPos p : logs) {
            BlockState below = world.getBlockState(p.below());
            if (!below.is(BlockTags.LOGS) && below.is(BlockTags.DIRT)) {
                bases.add(p);
                if (baseLog == null) {
                    baseLog = world.getBlockState(p);
                }
            }
        }
        bases.sort(Comparator.comparingInt(BlockPos::getY));
        Item sapling = baseLog == null ? null : saplingFor(baseLog, leaves);
        AABB bounds = new AABB(minX, minY, minZ, maxX + 1, top + 1, maxZ + 1);
        return new Tree(logs, bases, sapling, bounds);
    }

    @Nullable
    private static BlockState naturalLeavesNear(ClientLevel world, BlockPos log) {
        for (BlockPos p : BlockPos.betweenClosed(log.offset(-LEAF_RADIUS, -LEAF_RADIUS, -LEAF_RADIUS), log.offset(LEAF_RADIUS, LEAF_RADIUS, LEAF_RADIUS))) {
            BlockState state = world.getBlockState(p);
            if (state.is(BlockTags.LEAVES)) {
                // Player-placed leaves are persistent; natural ones are not.
                if (!state.hasProperty(LeavesBlock.PERSISTENT) || !state.getValue(LeavesBlock.PERSISTENT)) {
                    return state;
                }
            } else if (state.is(BlockTags.WART_BLOCKS) || state.is(Blocks.SHROOMLIGHT)) {
                // Huge fungi: crimson/warped stems. Felled, but not replanted.
                return state;
            }
        }
        return null;
    }

    /** oak_log -> oak_sapling, mangrove -> propagule, azalea leaves -> azalea; null if none. */
    @Nullable
    private static Item saplingFor(BlockState log, BlockState leaves) {
        if (leaves.is(Blocks.AZALEA_LEAVES) || leaves.is(Blocks.FLOWERING_AZALEA_LEAVES)) {
            return Items.AZALEA;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(log.getBlock());
        String path = id.getPath();
        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }
        String wood;
        if (path.endsWith("_log")) {
            wood = path.substring(0, path.length() - 4);
        } else if (path.endsWith("_wood")) {
            wood = path.substring(0, path.length() - 5);
        } else {
            return null;
        }
        if (wood.equals("mangrove")) {
            return Items.MANGROVE_PROPAGULE;
        }
        ResourceLocation saplingId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), wood + "_sapling");
        return BuiltInRegistries.ITEM.containsKey(saplingId) ? BuiltInRegistries.ITEM.get(saplingId) : null;
    }

    @Override
    public void suspend(Minecraft client) {
        cancel(client);
        if (this.phase == Phase.CHOP && this.current != null) {
            // Start this tree over when we get the queue back.
            this.trees.add(this.current);
            this.phase = Phase.NEXT;
        }
        this.walk = null;
    }

    @Override
    public void cancel(Minecraft client) {
        if (this.chop != null) {
            this.chop.cancel(client);
            this.chop = null;
        }
    }
}
