package com.testrts.rtsclient.task;

import net.minecraft.client.Minecraft;

/** One queued job. Ticked on the client thread until it reports DONE or FAILED. */
public interface RtsTask {
    enum Status {
        RUNNING,
        DONE,
        FAILED
    }

    /** Short label for the task list. */
    String label();

    /** What the task is doing right now, for the task list. */
    default String detail() {
        return "";
    }

    Status tick(Minecraft client, TaskQueue queue);

    /** Stop any work in progress (the queue also stops Baritone). */
    default void cancel(Minecraft client) {
    }

    /**
     * Another order is taking over (reordered or this one paused). The task stays queued and is
     * ticked again later; it should stop what it's doing and be able to start over.
     */
    default void suspend(Minecraft client) {
        cancel(client);
    }
}
