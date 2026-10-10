package com.testrts.rtsclient.task;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Runs tasks one after another. A task may push a prerequisite in front of itself. */
public final class TaskQueue {
    private static final TaskQueue INSTANCE = new TaskQueue();

    private final Deque<RtsTask> tasks = new ArrayDeque<>();
    private final Set<RtsTask> paused = Collections.newSetFromMap(new IdentityHashMap<>());
    /** The task ticked last; when another one takes over it is suspended so it can restart later. */
    private RtsTask running;
    private int idleTicks;

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

    public boolean isPaused(RtsTask task) {
        return this.paused.contains(task);
    }

    /** Pause or resume one order; paused orders are skipped until resumed. */
    public void togglePause(RtsTask task) {
        if (!this.paused.remove(task) && this.tasks.contains(task)) {
            this.paused.add(task);
        }
    }

    /** Move an order to position {@code index} in the queue (0 = run it next). */
    public void move(RtsTask task, int index) {
        List<RtsTask> list = new ArrayList<>(this.tasks);
        if (!list.remove(task)) {
            return;
        }
        list.add(Math.max(0, Math.min(index, list.size())), task);
        this.tasks.clear();
        this.tasks.addAll(list);
    }

    public void runFirst(RtsTask task) {
        this.paused.remove(task);
        move(task, 0);
    }

    public void clear(Minecraft client) {
        for (RtsTask task : this.tasks) {
            task.cancel(client);
        }
        this.tasks.clear();
        this.paused.clear();
        this.running = null;
        Engine.stop();
        if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
    }

    /** Remove one task. Cancelling the running one also stops Baritone. */
    public void remove(Minecraft client, RtsTask task) {
        boolean wasHead = this.tasks.peekFirst() == task;
        this.paused.remove(task);
        if (this.tasks.remove(task)) {
            if (this.running == task) {
                this.running = null;
            }
            task.cancel(client);
            if (wasHead) {
                Engine.stop();
            }
        }
    }

    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            if (!this.tasks.isEmpty()) {
                // Leaving the world: keep the orders for next time before dropping them.
                QueueStore.autosave();
                this.tasks.clear();
            }
            return;
        }
        RtsTask head = null;
        for (RtsTask t : this.tasks) {
            if (!this.paused.contains(t)) {
                head = t;
                break;
            }
        }
        if (this.running != null && this.running != head && this.tasks.contains(this.running)) {
            // Reordered or paused: put the old one on hold so it starts cleanly when its turn comes.
            this.running.suspend(client);
            Engine.stop();
        }
        this.running = head;
        if (head == null) {
            if (this.tasks.isEmpty() && ++this.idleTicks % 200 == 0 && com.testrts.rtsclient.config.RtsConfig.get().autoRepair) {
                CompletedBuilds.queueRepairs(client.level, true);
            }
            return;
        }
        this.idleTicks = 0;
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
            this.paused.remove(head);
            if (this.running == head) {
                this.running = null;
            }
        }
    }
}
