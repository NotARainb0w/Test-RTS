package com.testrts.rtsclient.task;

import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

/** Walk into reach of a block and right-click it. Any screen it opens is left for the player. */
public final class InteractTask implements RtsTask {
    private final BlockPos pos;
    private final BlockHitResult hit;
    private final String blockName;
    private final WalkPhase walk;

    public InteractTask(BlockHitResult hit, String blockName) {
        this.pos = hit.getBlockPos().immutable();
        this.hit = hit;
        this.blockName = blockName;
        this.walk = new WalkPhase(this.pos);
    }

    @Override
    public String label() {
        return "Use " + this.blockName;
    }

    @Override
    public String detail() {
        return "at " + this.pos.toShortString();
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        WalkPhase.Result r = this.walk.tick(client);
        if (r == WalkPhase.Result.WALKING) {
            return Status.RUNNING;
        }
        if (r == WalkPhase.Result.UNREACHABLE) {
            Messages.warn("Can't reach " + this.blockName);
            return Status.FAILED;
        }
        Reach.interact(client, this.pos, this.hit);
        return Status.DONE;
    }
}
