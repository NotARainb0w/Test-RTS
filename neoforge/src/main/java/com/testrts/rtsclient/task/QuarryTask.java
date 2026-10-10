package com.testrts.rtsclient.task;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Digs out a box layer by layer, top down, through the same engine as clear orders. Each layer is
 * run as an inline {@link BuildTask}; when the inventory is nearly full the layer is paused, the
 * loot is stored in linked storage, and the layer is picked up again.
 */
public final class QuarryTask implements RtsTask {
    /** Store the loot once this few (or fewer) inventory slots are left. */
    private static final int MIN_FREE_SLOTS = 2;
    private static final int MAX_STORE_ROUNDS = 64;

    private final BlockPos min;
    private final BlockPos max;
    private int layerY;
    private BuildTask layer;
    private int layerSize;
    private int dug;
    private int storeRounds;
    /** A store trip just ran; if the inventory is still full afterwards, give up. */
    private boolean justStored;
    private boolean finished;
    private String detail = "starting";

    public QuarryTask(BlockPos a, BlockPos b) {
        this.min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        this.max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        this.layerY = this.max.getY();
    }

    @Override
    public String label() {
        int sx = this.max.getX() - this.min.getX() + 1;
        int sy = this.max.getY() - this.min.getY() + 1;
        int sz = this.max.getZ() - this.min.getZ() + 1;
        return "Quarry " + sx + "x" + sy + "x" + sz;
    }

    @Override
    public String detail() {
        if (this.layer != null) {
            int done = this.max.getY() - this.layerY + 1;
            int total = this.max.getY() - this.min.getY() + 1;
            return "layer " + done + "/" + total + " (y=" + this.layerY + ") - " + this.layer.detail();
        }
        return this.detail;
    }

    @Override
    public Status tick(Minecraft client, TaskQueue queue) {
        if (client.player == null || client.level == null) {
            return Status.FAILED;
        }
        if (this.finished) {
            return Status.DONE;
        }
        if (Jobs.emptySlots(client.player.getInventory()) <= MIN_FREE_SLOTS) {
            return storeLoot(client, queue);
        }
        this.justStored = false;

        if (this.layer != null) {
            Status s = this.layer.tick(client, queue);
            if (s == Status.RUNNING) {
                return Status.RUNNING;
            }
            this.dug += this.layerSize - MaterialPlanner.remaining(client.level, this.layer.blocks());
            this.layer = null;
            this.layerY--;
            // The layer may have pushed a drop pickup in front of us; let it run first.
            return Status.RUNNING;
        }
        while (this.layerY >= this.min.getY()) {
            Map<BlockPos, BlockState> order = layerOrder(client.level, this.layerY);
            if (!order.isEmpty()) {
                startLayer(order);
                return Status.RUNNING;
            }
            this.layerY--;
        }
        // All layers done: one last sweep for drops, then report.
        this.finished = true;
        this.detail = "collecting drops";
        Messages.success(label() + " finished (" + this.dug + " blocks dug)");
        queue.pushFront(new CollectDropsTask(new AABB(this.min.getX(), this.min.getY(), this.min.getZ(),
                this.max.getX() + 1, this.max.getY() + 1, this.max.getZ() + 1)));
        return Status.RUNNING;
    }

    private void startLayer(Map<BlockPos, BlockState> order) {
        this.layerSize = order.size();
        this.layer = new BuildTask("Quarry layer y=" + this.layerY + " (" + order.size() + ")", order, true);
    }

    /** Pause the current layer, store the loot, and redo what's left of the layer afterwards. */
    private Status storeLoot(Minecraft client, TaskQueue queue) {
        if (StorageManager.get().linked().isEmpty()) {
            Messages.error(label() + " stopped: inventory full and no linked storage - link a chest and queue it again");
            cancel(client);
            return Status.FAILED;
        }
        if (this.justStored || this.storeRounds >= MAX_STORE_ROUNDS) {
            Messages.error(label() + " stopped: inventory still full after storing (storage full?)");
            cancel(client);
            return Status.FAILED;
        }
        if (this.layer != null) {
            this.layer.cancel(client);
            this.dug += this.layerSize - MaterialPlanner.remaining(client.level, this.layer.blocks());
            // Recount this layer from the world when we come back.
            this.layer = null;
        }
        this.storeRounds++;
        this.justStored = true;
        this.detail = "storing loot";
        Messages.info(label() + ": inventory full, storing loot");
        queue.pushFront(new StoreItemsTask("Store quarry loot", QuarryTask::isLoot, scaffoldKeep(), true));
        return Status.RUNNING;
    }

    /** Everything except tools, armour and food goes to storage. */
    private static boolean isLoot(ItemStack stack) {
        return !stack.isDamageableItem() && !stack.has(DataComponents.FOOD);
    }

    /** Keep a stack of the configured scaffolding blocks so the builder can still climb out. */
    private static Map<Item, Integer> scaffoldKeep() {
        Map<Item, Integer> keep = new HashMap<>();
        for (String id : RtsConfig.get().scaffoldBlocks) {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            if (parsed != null && BuiltInRegistries.ITEM.containsKey(parsed)) {
                keep.put(BuiltInRegistries.ITEM.get(parsed), 64);
            }
        }
        return keep;
    }

    /** Positions in one layer that still need digging: no air, fluids, bedrock-likes or linked storage. */
    private Map<BlockPos, BlockState> layerOrder(ClientLevel world, int y) {
        Map<BlockPos, BlockState> order = new LinkedHashMap<>();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos p : BlockPos.betweenClosed(this.min.getX(), y, this.min.getZ(), this.max.getX(), y, this.max.getZ())) {
            if (!world.isLoaded(p)) {
                continue;
            }
            BlockState state = world.getBlockState(p);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.is(Blocks.BUBBLE_COLUMN)) {
                continue;
            }
            if (state.getDestroySpeed(world, p) < 0) {
                continue;
            }
            if (StorageManager.isContainer(world, p) && StorageManager.get().isLinked(world, p)) {
                continue;
            }
            order.put(p.immutable(), air);
        }
        return order;
    }

    @Override
    public void cancel(Minecraft client) {
        if (this.layer != null) {
            this.layer.cancel(client);
            this.layer = null;
        }
    }
}
