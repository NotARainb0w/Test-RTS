package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/** Opens linked containers one by one so their contents get recorded. */
public final class IndexStorageTask implements RtsTask {
    private final Deque<BlockPos> pending = new ArrayDeque<>();
    private final int total;
    private ContainerVisitTask visit;

    public IndexStorageTask(BlockPos near, boolean onlyUnindexed) {
        List<StorageManager.LinkedContainer> source = onlyUnindexed
                ? StorageManager.get().unindexed()
                : new ArrayList<>(StorageManager.get().linked());
        // Nearest-neighbour order keeps the walk short.
        List<BlockPos> left = new ArrayList<>();
        source.forEach(c -> left.add(c.pos()));
        BlockPos from = near;
        while (!left.isEmpty()) {
            BlockPos origin = from;
            BlockPos next = left.stream().min(Comparator.comparingDouble(p -> p.distSqr(origin))).orElseThrow();
            left.remove(next);
            this.pending.add(next);
            from = next;
        }
        this.total = this.pending.size();
    }

    @Override
    public String label() {
        return "Index storage";
    }

    @Override
    public String detail() {
        int done = this.total - this.pending.size() - (this.visit != null ? 1 : 0);
        return done + " / " + this.total + (this.visit != null ? " - " + this.visit.detail() : "");
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        if (this.visit != null) {
            if (this.visit.tick(client, queue) == Status.RUNNING) {
                return Status.RUNNING;
            }
            this.visit = null;
        }
        BlockPos next = this.pending.pollFirst();
        if (next == null) {
            Messages.success("Indexed " + this.total + " container(s)");
            return Status.DONE;
        }
        this.visit = new ContainerVisitTask(next, "Index", (c, handler) -> true);
        return Status.RUNNING;
    }

    @Override
    public void cancel(Minecraft client) {
        if (this.visit != null) {
            this.visit.cancel(client);
        }
    }
}
