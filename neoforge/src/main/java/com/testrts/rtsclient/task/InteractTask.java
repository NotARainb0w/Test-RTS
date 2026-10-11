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
    /** Use this item on the block (bone meal, a hoe, flint and steel...), or null for an empty hand. */
    @org.jetbrains.annotations.Nullable
    private final net.minecraft.world.item.Item item;

    public InteractTask(BlockHitResult hit, String blockName) {
        this.pos = hit.getBlockPos().immutable();
        this.hit = hit;
        this.blockName = blockName;
        this.walk = new WalkPhase(this.pos);
        this.item = null;
    }

    public InteractTask(BlockHitResult hit, String blockName, @org.jetbrains.annotations.Nullable net.minecraft.world.item.Item item) {
        this.pos = hit.getBlockPos().immutable();
        this.hit = hit;
        this.blockName = blockName;
        this.walk = new WalkPhase(this.pos);
        this.item = item;
    }

    @Override
    public String label() {
        return this.item == null ? "Use " + this.blockName
                : "Use " + new net.minecraft.world.item.ItemStack(this.item).getHoverName().getString() + " on " + this.blockName;
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
        if (this.item != null && !com.testrts.rtsclient.exec.Inventories.hold(client, this.item)) {
            Messages.warn(label() + ": you don't have it");
            return Status.FAILED;
        }
        Reach.interact(client, this.pos, this.hit);
        return Status.DONE;
    }
}
