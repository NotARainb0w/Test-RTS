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

    private final String label;
    private final Map<BlockPos, BlockState> blocks;
    private final boolean clearing;
    private boolean started;
    private int grace;
    private int fetchRounds;
    private Map<Item, Integer> lastMissing = Collections.emptyMap();
    private boolean stallReported;
    private int pausedTicks;
    private String detail = "waiting";

    public BuildTask(String label, Map<BlockPos, BlockState> blocks, boolean clearing) {
        this.label = label;
        this.blocks = new LinkedHashMap<>(blocks);
        this.clearing = clearing;
    }

    public Map<BlockPos, BlockState> blocks() {
        return Collections.unmodifiableMap(this.blocks);
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
        int left = MaterialPlanner.remaining(client.world, this.blocks);
        if (!Engine.isBuilding()) {
            if (left > 0) {
                Messages.warn(this.label + ": " + left + " block(s) left that Baritone couldn't do");
            } else {
                Messages.success(this.label + " finished");
            }
            return Status.DONE;
        }
        this.detail = left + " / " + this.blocks.size() + " left";
        if (Engine.isBuilderPaused()) {
            this.pausedTicks++;
            Map<Item, Integer> missing = this.clearing ? Map.of() : MaterialPlanner.missing(client, this.blocks);
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
            if (this.pausedTicks % 100 == 0) {
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
            }
        });
        return available;
    }

    @Override
    public void cancel(MinecraftClient client) {
        if (this.started) {
            Engine.stop();
        }
    }
}
