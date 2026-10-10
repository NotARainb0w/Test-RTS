package com.testrts.rtsclient.task;

import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** Send the player's body somewhere. */
public final class MoveTask implements RtsTask {
    private final BlockPos feet;
    private int ticks = -1;

    public MoveTask(BlockPos feet) {
        this.feet = feet.toImmutable();
    }

    public BlockPos target() {
        return this.feet;
    }

    @Override
    public String label() {
        return "Move";
    }

    @Override
    public String detail() {
        return "to " + this.feet.toShortString();
    }

    @Override
    public Status tick(MinecraftClient client, TaskQueue queue) {
        if (this.ticks < 0) {
            if (!Engine.goTo(this.feet)) {
                Messages.warn("No walkable path to " + this.feet.toShortString());
                return Status.FAILED;
            }
            this.ticks = 0;
            return Status.RUNNING;
        }
        this.ticks++;
        if (this.ticks < 6 || Engine.isMoving()) {
            return Status.RUNNING;
        }
        return Status.DONE;
    }
}
