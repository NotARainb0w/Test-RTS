package com.testrts.rtsclient.task;

import com.testrts.rtsclient.exec.Engine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Shared "get within reach of this block" step, using Baritone for the walking. */
final class WalkPhase {
    enum Result {
        IN_REACH,
        WALKING,
        UNREACHABLE
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final int START_GRACE_TICKS = 6;
    /**
     * An attempt is given up when the player hasn't got a block closer for this long, whatever the
     * engine says (it may think it's still walking while going round in circles).
     */
    private static final int NO_PROGRESS_TICKS = 20 * 30;

    private final BlockPos target;
    private int attempts;
    private int ticksSinceRequest = -1;
    private double bestDistance;
    private int sinceCloser;

    WalkPhase(BlockPos target) {
        this.target = target;
    }

    Result tick(Minecraft client) {
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
                if (!gettingCloser(client)) {
                    // Stop this attempt; the next one plans from where we are now.
                    Engine.stop();
                    this.ticksSinceRequest = -1;
                } else {
                    return Result.WALKING;
                }
            }
        }
        if (this.attempts >= MAX_ATTEMPTS || !Engine.goNextTo(this.target)) {
            return Result.UNREACHABLE;
        }
        this.attempts++;
        this.ticksSinceRequest = 0;
        this.bestDistance = Double.MAX_VALUE;
        this.sinceCloser = 0;
        return Result.WALKING;
    }

    /** False once the player has gone {@link #NO_PROGRESS_TICKS} without getting a block closer. */
    private boolean gettingCloser(Minecraft client) {
        if (client.player == null) {
            return true;
        }
        double d = client.player.getEyePosition().distanceTo(Vec3.atCenterOf(this.target));
        if (d < this.bestDistance - 1.0) {
            this.bestDistance = d;
            this.sinceCloser = 0;
            return true;
        }
        return ++this.sinceCloser < NO_PROGRESS_TICKS;
    }
}
