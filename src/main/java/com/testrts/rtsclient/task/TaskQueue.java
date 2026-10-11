package com.testrts.rtsclient.task;

import com.testrts.rtsclient.RtsClientMod;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.MinecraftClient;

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
    /**
     * Orders only run once Start was pressed; Pause stops them where they are. When the queue runs
     * empty it goes back to waiting, so new orders wait for Start again (unless autoStart is on).
     */
    private boolean started;

    public static TaskQueue get() {
        return INSTANCE;
    }

    public void add(RtsTask task) {
        this.tasks.addLast(task);
        startIfAuto();
    }

    private void startIfAuto() {
        if (com.testrts.rtsclient.config.RtsConfig.get().autoStart) {
            this.started = true;
        }
    }

    public boolean isStarted() {
        return this.started;
    }

    /** Start (or carry on with) the queued orders. */
    public void start() {
        this.started = true;
    }

    /** Stop working on every order right where we are; Start carries on. */
    public void pauseAll(MinecraftClient client) {
        if (!this.started) {
            return;
        }
        this.started = false;
        if (this.running != null) {
            this.running.suspend(client);
            this.running = null;
        }
        Engine.stop();
    }

    /** Run {@code task} before whatever is currently at the head. */
    public void pushFront(RtsTask task) {
        this.tasks.addFirst(task);
        startIfAuto();
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

    public void clear(MinecraftClient client) {
        for (RtsTask task : this.tasks) {
            task.cancel(client);
        }
        this.tasks.clear();
        this.paused.clear();
        this.running = null;
        this.started = false;
        Engine.stop();
        if (client.player != null && client.player.currentScreenHandler != client.player.playerScreenHandler) {
            client.player.closeHandledScreen();
        }
    }

    /** Remove one task. Cancelling the running one also stops Baritone. */
    public void remove(MinecraftClient client, RtsTask task) {
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

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            if (!this.tasks.isEmpty()) {
                // Leaving the world: keep the orders for next time before dropping them.
                QueueStore.autosave();
                this.tasks.clear();
            }
            this.started = false;
            return;
        }
        if (this.tasks.isEmpty()) {
            // Ran out of orders: new ones wait for Start again.
            this.started = false;
            this.running = null;
            if (++this.idleTicks % 200 == 0 && com.testrts.rtsclient.config.RtsConfig.get().autoRepair
                    && CompletedBuilds.queueRepairs(client.world, true) > 0) {
                this.started = true; // auto-repair runs by itself
            }
            return;
        }
        if (!this.started) {
            // Waiting for Start, or paused: whatever was running stops where it is.
            if (this.running != null) {
                this.running.suspend(client);
                this.running = null;
                Engine.stop();
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
            return; // every order is paused on its own
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
