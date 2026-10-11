package com.testrts.rtsclient.task;

import com.testrts.rtsclient.util.Messages;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers recently finished builds so gaps (blocks that never got placed, or got broken later)
 * can be found and filled in again.
 */
public final class CompletedBuilds {
    private static final int KEEP = 16;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    private record Entry(String label, Map<BlockPos, BlockState> blocks) {
    }

    private CompletedBuilds() {
    }

    public static void record(String label, Map<BlockPos, BlockState> blocks) {
        String name = label.replaceFirst(" \\(\\d+\\)$", "");
        ENTRIES.removeIf(e -> e.label().equals(name) && e.blocks().keySet().equals(blocks.keySet()));
        ENTRIES.addFirst(new Entry(name, new LinkedHashMap<>(blocks)));
        while (ENTRIES.size() > KEEP) {
            ENTRIES.removeLast();
        }
    }

    public static int size() {
        return ENTRIES.size();
    }

    public static void forgetAll() {
        ENTRIES.clear();
    }

    /**
     * Queue repair orders for every remembered build with missing or wrong blocks.
     *
     * @param quiet automatic check: say nothing when everything is fine, and leave builds alone that
     *              are mostly gone (probably torn down on purpose)
     * @return the number of blocks queued
     */
    public static int queueRepairs(ClientWorld world, boolean quiet) {
        if (world == null) {
            return 0;
        }
        int total = 0;
        for (Entry e : ENTRIES) {
            Map<BlockPos, BlockState> gaps = new LinkedHashMap<>();
            int loaded = 0;
            for (Map.Entry<BlockPos, BlockState> b : e.blocks().entrySet()) {
                if (!world.isChunkLoaded(b.getKey())) {
                    continue;
                }
                loaded++;
                if (!MaterialPlanner.matches(world.getBlockState(b.getKey()), b.getValue())) {
                    gaps.put(b.getKey(), b.getValue());
                }
            }
            if (gaps.isEmpty() || quiet && gaps.size() * 2 > loaded) {
                continue;
            }
            TaskQueue.get().add(new BuildTask("Repair " + e.label() + " (" + gaps.size() + ")", gaps, false));
            total += gaps.size();
        }
        if (total > 0) {
            Messages.info("Repairing " + total + " missing block(s) in finished builds");
        } else if (!quiet) {
            Messages.success(ENTRIES.isEmpty() ? "No finished builds to check yet" : "All finished builds are complete");
        }
        return total;
    }
}
