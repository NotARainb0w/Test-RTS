package com.testrts.rtsclient.task;

import net.minecraft.client.MinecraftClient;

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

    Status tick(MinecraftClient client, TaskQueue queue);

    /** Stop any work in progress (the queue also stops Baritone). */
    default void cancel(MinecraftClient client) {
    }
}
