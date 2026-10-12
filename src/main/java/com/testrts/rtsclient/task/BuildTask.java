package com.testrts.rtsclient.task;

import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A build or clear order executed by Baritone's builder. Before starting, and whenever the
 * builder pauses for lack of blocks, missing materials are fetched from linked storage.
 */
public final class BuildTask implements RtsTask {
    private static final int START_GRACE_TICKS = 10;
    private static final int MAX_FETCH_ROUNDS = 4;
    private static final int MAX_RETRIES = 2;

    private final String label;
    private final Map<BlockPos, BlockState> blocks;
    private final boolean clearing;
    private boolean started;
    private int grace;
    private int fetchRounds;
    private Map<Item, Integer> lastMissing = Collections.emptyMap();
    private boolean stallReported;
    private int pausedTicks;
    private int countIn;
    /** Blocks still to do at the last count, or -1 before the first count. */
    private int left = -1;
    private int retries;
    private net.minecraft.util.math.Box bounds;
    private String detail = "waiting";

    public BuildTask(String label, Map<BlockPos, BlockState> blocks, boolean clearing) {
        this.label = label;
        this.blocks = new LinkedHashMap<>(blocks);
        this.clearing = clearing;
    }

    public Map<BlockPos, BlockState> blocks() {
        return Collections.unmodifiableMap(this.blocks);
    }

    /** 0..1 done, or -1 while unknown (not started yet). */
    public float progress() {
        if (this.left < 0 || this.blocks.isEmpty()) {
            return -1F;
        }
        return 1F - (float) this.left / this.blocks.size();
    }

    /** Bounding box of the order, computed once (used for drawing every frame). */
    public net.minecraft.util.math.Box bounds() {
        if (this.bounds == null) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos p : this.blocks.keySet()) {
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
                maxZ = Math.max(maxZ, p.getZ());
            }
            this.bounds = new net.minecraft.util.math.Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
        }
        return this.bounds;
    }

    public boolean clearing() {
        return this.clearing;
    }

    @Override
    public String label() {
        return this.label;
    }

    @Override
    public String detail() {
        return this.detail;
    }

    @Override
    public Status tick(MinecraftClient client, TaskQueue queue) {
        if (client.world == null) {
            return Status.FAILED;
        }
        if (!this.started) {
            if (!this.clearing && tryFetch(client, queue)) {
                return Status.RUNNING;
            }
            if (MaterialPlanner.remaining(client.world, this.blocks) == 0) {
                return Status.DONE;
            }
            Engine.build("rts-" + (this.clearing ? "clear" : "build"), this.blocks,
                    RtsConfig.get().strictOrientation, RtsConfig.get().manageBaritoneSettings);
            this.started = true;
            this.grace = START_GRACE_TICKS;
            this.stallReported = false;
            this.detail = "starting";
            return Status.RUNNING;
        }
        if (this.grace > 0) {
            this.grace--;
            return Status.RUNNING;
        }
        if (!Engine.isBuilding()) {
            int left = MaterialPlanner.remaining(client.world, this.blocks);
            if (left > 0 && this.retries < MAX_RETRIES) {
                // Some blocks got missed (unreachable at the time, rejected, knocked out): go again
                // for just those. Missing materials are fetched first by the normal start path.
                this.retries++;
                Map<BlockPos, BlockState> leftover = new LinkedHashMap<>();
                for (Map.Entry<BlockPos, BlockState> e : this.blocks.entrySet()) {
                    BlockState now = client.world.getBlockState(e.getKey());
                    boolean ok = MaterialPlanner.matches(now, e.getValue());
                    if (!ok) {
                        leftover.put(e.getKey(), e.getValue());
                    }
                }
                Messages.info(this.label + ": " + leftover.size() + " block(s) missed, going back for them");
                Engine.build("rts-retry", leftover, RtsConfig.get().strictOrientation, RtsConfig.get().manageBaritoneSettings);
                this.grace = START_GRACE_TICKS;
                this.detail = "retrying " + leftover.size();
                return Status.RUNNING;
            }
            if (left > 0) {
                Messages.warn(this.label + ": " + left + " block(s) left that couldn't be done");
            } else {
                Messages.success(this.label + " finished");
            }
            if (!this.clearing) {
                CompletedBuilds.record(this.label, this.blocks);
            }
            if (this.clearing && RtsConfig.get().collectDrops) {
                queue.pushFront(new CollectDropsTask(bounds()));
            }
            return Status.DONE;
        }
        // Counting is O(order size); once a second is plenty for the task list.
        if (--this.countIn <= 0) {
            this.countIn = 20;
            this.left = MaterialPlanner.remaining(client.world, this.blocks);
            this.detail = this.left + " / " + this.blocks.size() + " left";
        }
        if (Engine.isBuilderPaused()) {
            if (this.pausedTicks++ % 20 != 0) {
                return Status.RUNNING;
            }
            Map<Item, Integer> missing = this.clearing ? Map.of() : MaterialPlanner.missing(client, this.blocks);
            Item engineWants = Engine.missingItem();
            if (missing.isEmpty() && engineWants != null) {
                // Not a material of the order itself: scaffolding blocks.
                missing = Map.of(engineWants, 64);
            }
            if (!missing.isEmpty() && canFetch(missing)) {
                // Builder can't continue without blocks: stop it, fetch, then this task restarts it.
                Engine.stop();
                this.started = false;
                queueFetch(missing, queue);
                return Status.RUNNING;
            }
            if (!this.stallReported) {
                Messages.warn(this.label + " stuck" + (missing.isEmpty() ? "" : ", missing " + MaterialPlanner.describe(missing, 3)));
                this.stallReported = true;
            }
            this.detail = missing.isEmpty() ? "stuck" : "missing " + MaterialPlanner.describe(missing, 2);
            // Every few seconds, nudge the builder in case the player sorted things out by hand.
            if (this.pausedTicks > 20 && this.pausedTicks % 100 == 1) {
                Engine.resumeBuilder();
            }
        } else {
            this.pausedTicks = 0;
            this.stallReported = false;
        }
        return Status.RUNNING;
    }

    /** Queue a fetch in front of this task if storage can cover part of what's missing. */
    private boolean tryFetch(MinecraftClient client, TaskQueue queue) {
        Map<Item, Integer> missing = MaterialPlanner.missing(client, this.blocks);
        if (missing.isEmpty() || !canFetch(missing)) {
            return false;
        }
        queueFetch(missing, queue);
        return true;
    }

    private boolean canFetch(Map<Item, Integer> missing) {
        // Same shortfall as last round means the previous fetch didn't help; don't loop.
        return this.fetchRounds < MAX_FETCH_ROUNDS && !missing.equals(this.lastMissing) && !fromStorage(missing).isEmpty();
    }

    private void queueFetch(Map<Item, Integer> missing, TaskQueue queue) {
        this.lastMissing = missing;
        this.fetchRounds++;
        this.detail = "fetching materials";
        queue.pushFront(new FetchTask(fromStorage(missing)));
    }

    private static Map<Item, Integer> fromStorage(Map<Item, Integer> missing) {
        Map<Item, Integer> available = new LinkedHashMap<>();
        missing.forEach((item, n) -> {
            int stored = StorageManager.get().total(item);
            if (stored > 0) {
                available.put(item, Math.min(n, stored));
            } else if (com.testrts.rtsclient.storage.MeNetwork.anyMightHave(MinecraftClient.getInstance(), item)) {
                // An AE2 network we haven't looked into yet: opening a terminal is how to find out.
                available.put(item, n);
            }
        });
        return available;
    }

    @Override
    public void suspend(MinecraftClient client) {
        if (this.started) {
            Engine.stop();
        }
        this.started = false;
        this.detail = "on hold";
    }

    @Override
    public void cancel(MinecraftClient client) {
        if (this.started) {
            Engine.stop();
        }
    }
}
