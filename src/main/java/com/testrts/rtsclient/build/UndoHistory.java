package com.testrts.rtsclient.build;

import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.TaskQueue;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** Remembers what was in the world before each order, so it can be put back. */
public final class UndoHistory {
    private static final int MAX = 20;
    private static final Deque<Entry> HISTORY = new ArrayDeque<>();

    private record Entry(String label, Map<BlockPos, BlockState> before) {
    }

    private UndoHistory() {
    }

    public static void push(String label, Map<BlockPos, BlockState> before) {
        HISTORY.addLast(new Entry(label, before));
        while (HISTORY.size() > MAX) {
            HISTORY.removeFirst();
        }
    }

    public static boolean isEmpty() {
        return HISTORY.isEmpty();
    }

    /** Queue orders that restore the blocks changed by the most recent order. */
    public static void undoLast(ClientWorld world) {
        Entry last = HISTORY.pollLast();
        if (last == null) {
            Messages.info("Nothing to undo");
            return;
        }
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        Map<BlockPos, BlockState> rebuild = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : last.before().entrySet()) {
            BlockState now = world.getBlockState(e.getKey());
            BlockState was = e.getValue();
            if (now.equals(was)) {
                continue;
            }
            if (was.isAir() || !was.getFluidState().isEmpty()) {
                clear.put(e.getKey(), net.minecraft.block.Blocks.AIR.getDefaultState());
            } else {
                rebuild.put(e.getKey(), was);
            }
        }
        if (clear.isEmpty() && rebuild.isEmpty()) {
            Messages.info("Nothing changed by \"" + last.label() + "\" yet");
            return;
        }
        if (!clear.isEmpty()) {
            TaskQueue.get().add(new BuildTask("Undo: clear " + clear.size(), clear, true));
        }
        if (!rebuild.isEmpty()) {
            TaskQueue.get().add(new BuildTask("Undo: restore " + rebuild.size(), rebuild, false));
        }
        Messages.info("Undoing \"" + last.label() + "\"");
    }
}
