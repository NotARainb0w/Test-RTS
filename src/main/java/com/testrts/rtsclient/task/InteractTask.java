package com.testrts.rtsclient.task;

import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/** Walk into reach of a block and right-click it. Any screen it opens is left for the player. */
public final class InteractTask implements RtsTask {
    private final BlockPos pos;
    private final BlockHitResult hit;
    private final String blockName;
    private final WalkPhase walk;

    public InteractTask(BlockHitResult hit, String blockName) {
        this.pos = hit.getBlockPos().toImmutable();
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
    public Status tick(MinecraftClient client, TaskQueue queue) {
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
