package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.MeNetwork;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Opens linked containers one by one so their contents get recorded, and reads the storage networks
 * (AE2, Refined Storage) through a wireless terminal or a linked terminal / grid.
 */
public final class IndexStorageTask implements RtsTask {
    private final Deque<BlockPos> pending = new ArrayDeque<>();
    private final int total;
    private final boolean onlyUnindexed;
    /** The visit in progress: a {@link ContainerVisitTask}, or a {@link MeFetchTask} reading a network. */
    private RtsTask visit;
    /** Networks still to read; null until the first tick (needs the client). */
    private java.util.Deque<MeNetwork> networks;
    private int networksRead;
    private final java.util.Set<MeNetwork> triedWireless = new java.util.HashSet<>();

    public IndexStorageTask(BlockPos near, boolean onlyUnindexed) {
        this.onlyUnindexed = onlyUnindexed;
        List<StorageManager.LinkedContainer> source = onlyUnindexed
                ? StorageManager.get().unindexed()
                : new ArrayList<>(StorageManager.get().linked());
        // Nearest-neighbour order keeps the walk short.
        List<BlockPos> left = new ArrayList<>();
        source.forEach(c -> left.add(c.pos()));
        BlockPos from = near;
        while (!left.isEmpty()) {
            BlockPos origin = from;
            BlockPos next = left.stream().min(Comparator.comparingDouble(p -> p.getSquaredDistance(origin))).orElseThrow();
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
    public Status tick(MinecraftClient client, TaskQueue queue) {
        if (this.networks == null) {
            this.networks = new java.util.ArrayDeque<>();
            for (MeNetwork n : MeNetwork.all()) {
                if (n.reachable(client) && (!this.onlyUnindexed || !n.indexed())) {
                    this.networks.add(n);
                }
            }
        }
        if (this.visit != null) {
            Status s = this.visit.tick(client, queue);
            if (s == Status.RUNNING) {
                return Status.RUNNING;
            }
            if (this.visit instanceof MeFetchTask me) {
                if (s == Status.DONE) {
                    this.networksRead++;
                } else if (me.wireless() && !me.network().terminals().isEmpty()) {
                    // The wireless terminal didn't work: read it at a linked terminal instead.
                    this.networks.addFirst(me.network());
                }
            }
            this.visit = null;
        }
        MeNetwork network = this.networks.pollFirst();
        if (network != null) {
            boolean wireless = network.wirelessUsable(client) && this.triedWireless.add(network);
            MeNetwork.Terminal terminal = wireless ? null : network.nearestTerminal(client.player.getBlockPos());
            if (wireless || terminal != null) {
                this.visit = new MeFetchTask(network, java.util.Map.of(), terminal);
            }
            return Status.RUNNING;
        }
        BlockPos next = this.pending.pollFirst();
        if (next == null) {
            Messages.success("Indexed " + this.total + " container(s)"
                    + (this.networksRead > 0 ? " and " + this.networksRead + " storage network(s)" : ""));
            return Status.DONE;
        }
        this.visit = new ContainerVisitTask(next, "Index", (c, handler) -> true);
        return Status.RUNNING;
    }

    @Override
    public void cancel(MinecraftClient client) {
        if (this.visit != null) {
            this.visit.cancel(client);
        }
    }
}
