package com.testrts.rtsclient.task;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Runs tasks one after another. A task may push a prerequisite in front of itself. */
public final class TaskQueue {
    private static final TaskQueue INSTANCE = new TaskQueue();

    private final Deque<RtsTask> tasks = new ArrayDeque<>();

    public static TaskQueue get() {
        return INSTANCE;
    }

    public void add(RtsTask task) {
        this.tasks.addLast(task);
    }

    /** Run {@code task} before whatever is currently at the head. */
    public void pushFront(RtsTask task) {
        this.tasks.addFirst(task);
    }

    public boolean isEmpty() {
        return this.tasks.isEmpty();
    }

    public int size() {
        return this.tasks.size();
    }

    public List<RtsTask> snapshot() {
        return new ArrayList<>(this.tasks);
    }

    public void clear(Minecraft client) {
        for (RtsTask task : this.tasks) {
            task.cancel(client);
        }
        this.tasks.clear();
        Engine.stop();
        if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
    }

    /** Remove one task. Cancelling the running one also stops Baritone. */
    public void remove(Minecraft client, RtsTask task) {
        boolean wasHead = this.tasks.peekFirst() == task;
        if (this.tasks.remove(task)) {
            task.cancel(client);
            if (wasHead) {
                Engine.stop();
            }
        }
    }

    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            if (!this.tasks.isEmpty()) {
                this.tasks.clear();
            }
            return;
        }
        RtsTask head = this.tasks.peekFirst();
        if (head == null) {
            return;
        }
        RtsTask.Status status;
        try {
            status = head.tick(client, this);
        } catch (RuntimeException e) {
            RtsClientMod.LOGGER.error("RTS task '{}' crashed", head.label(), e);
            Messages.error(head.label() + " failed: " + e.getMessage());
            status = RtsTask.Status.FAILED;
        }
        if (status != RtsTask.Status.RUNNING) {
            // The head may have pushed a prerequisite in front of itself, so remove by identity.
            this.tasks.remove(head);
        }
    }
}
