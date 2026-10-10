package com.testrts.rtsclient.task;

import com.testrts.rtsclient.exec.Engine;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;

/** After clearing an area, walk over the dropped items so they end up in the inventory. */
public final class CollectDropsTask implements RtsTask {
    private static final int MAX_TICKS = 20 * 60;
    private static final int PER_ITEM_TICKS = 20 * 6;

    private final AABB area;
    private int ticks;
    private ItemEntity target;
    private int targetTicks;

    public CollectDropsTask(AABB area) {
        this.area = area.inflate(4.0);
    }

    @Override
    public String label() {
        return "Collect drops";
    }

    @Override
    public String detail() {
        return this.target != null ? "picking up " + this.target.getItem().getHoverName().getString() : "looking";
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        if (client.level == null || client.player == null || ++this.ticks > MAX_TICKS) {
            return Status.DONE;
        }
        if (client.player.getInventory().getFreeSlot() < 0) {
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
        List<ItemEntity> items = client.level.getEntitiesOfClass(ItemEntity.class, this.area, e -> !e.isRemoved());
        if (items.isEmpty()) {
            return Status.DONE;
        }
        ItemEntity nearest = items.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(client.player)))
                .orElseThrow();
        this.target = nearest;
        this.targetTicks = 0;
        BlockPos feet = nearest.blockPosition();
        if (!Engine.goTo(feet)) {
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    @Override
    public void cancel(Minecraft client) {
        this.target = null;
    }
}
