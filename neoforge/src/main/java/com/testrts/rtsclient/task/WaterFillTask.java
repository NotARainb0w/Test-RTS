package com.testrts.rtsclient.task;

import com.testrts.rtsclient.exec.Creative;
import com.testrts.rtsclient.exec.Inventories;
import com.testrts.rtsclient.exec.Look;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fill spots with water from buckets, the way a player does: pour a water bucket where the pool should be, and
 * when the bucket is empty scoop it full again from the pool (two touching sources make new ones in between, so
 * one bucket fills a long channel or a whole lake bottom, layer by layer from the bottom).
 */
public final class WaterFillTask implements RtsTask {
    /** Turning, the click, and the water settling (sources form a few ticks after). */
    private static final int SETTLE_TICKS = 10;
    private static final int MAX_TRIES = 4;

    private final String label;
    private final List<BlockPos> cells;
    private final Map<BlockPos, Integer> tries = new HashMap<>();
    private final java.util.Set<BlockPos> given = new java.util.HashSet<>();
    @Nullable
    private BlockPos target;
    private boolean scooping;
    @Nullable
    private WalkPhase walk;
    private int wait;
    private boolean fetched;

    public WaterFillTask(String label, List<BlockPos> cells) {
        this.label = label;
        this.cells = new ArrayList<>(cells);
        // Lowest layer first; in each layer every other cell first, so the ones between fill themselves.
        this.cells.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingInt(p -> Math.floorMod(p.getX() + p.getZ(), 2)));
    }

    @Override
    public String label() {
        return this.label;
    }

    @Override
    public String detail() {
        return this.target == null ? "planning" : (this.scooping ? "filling the bucket at " : "pouring at ") + this.target.toShortString();
    }

    private static boolean source(ClientLevel world, BlockPos p) {
        FluidState f = world.getFluidState(p);
        return f.is(FluidTags.WATER) && f.isSource();
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        ClientLevel world = client.level;
        if (client.player == null || world == null || client.gameMode == null) {
            return Status.FAILED;
        }
        if (this.wait > 0) {
            this.wait--;
            return Status.RUNNING;
        }
        boolean creative = Creative.supplies(client);
        int full = creative ? 1 : Inventories.count(client, Items.WATER_BUCKET);
        int empty = creative ? 0 : Inventories.count(client, Items.BUCKET);
        if (this.target == null || (this.scooping ? !source(world, this.target) : source(world, this.target))) {
            this.walk = null;
            this.target = null;
            BlockPos next = nextToFill(world);
            if (next == null) {
                if (!this.given.isEmpty()) {
                    Messages.warn(this.label + ": " + this.given.size() + " spot(s) without water");
                }
                return Status.DONE;
            }
            if (full > 0) {
                this.target = next;
                this.scooping = false;
            } else if (empty > 0) {
                this.target = scoopFrom(world, client);
                this.scooping = true;
                if (this.target == null) {
                    Messages.warn(this.label + ": no water that refills itself yet - carry 2 water buckets "
                            + "(water poured two apart makes a third between them, and that one never runs out)");
                    return Status.FAILED;
                }
            } else {
                if (!this.fetched) {
                    this.fetched = true;
                    queue.pushFront(new FetchTask(Map.of(Items.WATER_BUCKET, 1)));
                    return Status.RUNNING;
                }
                Messages.warn(this.label + ": needs a water bucket");
                return Status.FAILED;
            }
        }
        if (this.walk == null) {
            this.walk = new WalkPhase(this.target);
        }
        WalkPhase.Result r = this.walk.tick(client);
        if (r == WalkPhase.Result.WALKING) {
            return Status.RUNNING;
        }
        if (r == WalkPhase.Result.UNREACHABLE || this.tries.merge(this.target, 1, Integer::sum) > MAX_TRIES) {
            this.given.add(this.target);
            this.target = null;
            return Status.RUNNING;
        }
        Vec3 aim = this.scooping ? Vec3.atCenterOf(this.target) : aimFor(world, this.target);
        if (aim == null) {
            this.given.add(this.target);
            this.target = null;
            return Status.RUNNING;
        }
        boolean holding = creative ? Creative.spawnInHand(client, Items.WATER_BUCKET)
                : Inventories.hold(client, this.scooping ? Items.BUCKET : Items.WATER_BUCKET);
        if (!holding) {
            return Status.FAILED;
        }
        // Buckets act on what the player looks at: turn to it, then use.
        var player = client.player;
        Look.thenDo(client, aim, () -> {
            if (client.gameMode != null && client.player == player) {
                client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                player.swing(InteractionHand.MAIN_HAND);
            }
        });
        this.wait = SETTLE_TICKS;
        return Status.RUNNING;
    }

    /**
     * Next spot to pour into: lowest first. A spot that two neighbouring sources (over solid ground or water)
     * will turn into a source by themselves is skipped, saving trips.
     */
    @Nullable
    private BlockPos nextToFill(ClientLevel world) {
        for (BlockPos c : this.cells) {
            if (source(world, c) || this.given.contains(c)) {
                continue;
            }
            int around = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (source(world, c.relative(d))) {
                    around++;
                }
            }
            if (around >= 2 && !world.getFluidState(c).isEmpty()) {
                continue; // fills itself
            }
            return c;
        }
        return null;
    }

    /** Where to aim a water bucket so it pours into {@code cell}: the floor under it, else a wall beside it. */
    @Nullable
    private static Vec3 aimFor(ClientLevel world, BlockPos cell) {
        BlockPos below = cell.below();
        if (world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) {
            return new Vec3(cell.getX() + 0.5, cell.getY() + 0.02, cell.getZ() + 0.5);
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = cell.relative(d);
            if (world.getBlockState(side).isFaceSturdy(world, side, d.getOpposite())) {
                return Vec3.atCenterOf(cell).add(d.getStepX() * 0.49, 0, d.getStepZ() * 0.49);
            }
        }
        return null;
    }

    /**
     * A source to fill the empty bucket from: only one with two source neighbours, which comes straight back
     * (scooping a lone source would just undo the last pour).
     */
    @Nullable
    private BlockPos scoopFrom(ClientLevel world, Minecraft client) {
        for (BlockPos c : this.cells) {
            if (!source(world, c)) {
                continue;
            }
            int around = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (source(world, c.relative(d))) {
                    around++;
                }
            }
            if (around >= 2) {
                return c;
            }
        }
        return null;
    }
}
