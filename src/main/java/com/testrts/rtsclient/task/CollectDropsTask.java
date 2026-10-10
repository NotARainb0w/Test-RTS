package com.testrts.rtsclient.task;

import com.testrts.rtsclient.exec.Engine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.List;

/** After clearing an area, walk over the dropped items so they end up in the inventory. */
public final class CollectDropsTask implements RtsTask {
    private static final int MAX_TICKS = 20 * 60;
    private static final int PER_ITEM_TICKS = 20 * 6;

    private final Box area;
    private int ticks;
    private ItemEntity target;
    private int targetTicks;

    public CollectDropsTask(Box area) {
        this.area = area.expand(4.0);
    }

    @Override
    public String label() {
        return "Collect drops";
    }

    @Override
    public String detail() {
        return this.target != null ? "picking up " + this.target.getStack().getName().getString() : "looking";
    }

    @Override
    public Status tick(MinecraftClient client, TaskQueue queue) {
        if (client.world == null || client.player == null || ++this.ticks > MAX_TICKS) {
            return Status.DONE;
        }
        if (client.player.getInventory().getEmptySlot() < 0) {
            return Status.DONE;
        }
        if (this.target != null) {
            if (this.target.isRemoved() || ++this.targetTicks > PER_ITEM_TICKS) {
                this.target = null;
                Engine.stop();
            } else {
                if (!Engine.isMoving() && this.targetTicks > 10) {
                    // Arrived (or gave up) but it's still there: let the next pass choose again.
                    this.target = null;
                }
                return Status.RUNNING;
            }
        }
        List<ItemEntity> items = client.world.getEntitiesByClass(ItemEntity.class, this.area, e -> !e.isRemoved());
        if (items.isEmpty()) {
            return Status.DONE;
        }
        ItemEntity nearest = items.stream()
                .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(client.player)))
                .orElseThrow();
        this.target = nearest;
        this.targetTicks = 0;
        BlockPos feet = nearest.getBlockPos();
        if (!Engine.goTo(feet)) {
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    @Override
    public void cancel(MinecraftClient client) {
        this.target = null;
    }
}
