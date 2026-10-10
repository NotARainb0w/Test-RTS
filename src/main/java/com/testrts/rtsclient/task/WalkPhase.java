package com.testrts.rtsclient.task;

import com.testrts.rtsclient.exec.Engine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** Shared "get within reach of this block" step, using Baritone for the walking. */
final class WalkPhase {
    enum Result {
        IN_REACH,
        WALKING,
        UNREACHABLE
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final int START_GRACE_TICKS = 6;

    private final BlockPos target;
    private int attempts;
    private int ticksSinceRequest = -1;

    WalkPhase(BlockPos target) {
        this.target = target;
    }

    Result tick(MinecraftClient client) {
        if (Reach.inReach(client, this.target)) {
            if (this.ticksSinceRequest >= 0 && Engine.isMoving()) {
                Engine.stop();
            }
            this.ticksSinceRequest = -1;
            return Result.IN_REACH;
        }
        if (this.ticksSinceRequest >= 0) {
            this.ticksSinceRequest++;
            // Baritone takes a few ticks to start pathing; don't mistake that for "arrived".
            if (this.ticksSinceRequest < START_GRACE_TICKS || Engine.isMoving()) {
                return Result.WALKING;
            }
        }
        if (this.attempts >= MAX_ATTEMPTS || !Engine.goNextTo(this.target)) {
            return Result.UNREACHABLE;
        }
        this.attempts++;
        this.ticksSinceRequest = 0;
        return Result.WALKING;
    }
}
