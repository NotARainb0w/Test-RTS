package com.testrts.rtsclient.storage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers which block we last right-clicked through the mod, and while the resulting
 * container screen is open, copies its contents into {@link StorageManager}.
 */
public final class ContainerTracker {
    private static final ContainerTracker INSTANCE = new ContainerTracker();

    @Nullable
    private BlockPos pendingPos;
    private int pendingTicks;
    @Nullable
    private BlockPos openPos;
    @Nullable
    private AbstractContainerMenu openHandler;

    public static ContainerTracker get() {
        return INSTANCE;
    }

    /** Call right before sending an interact packet for a block that may open a container. */
    public void expect(BlockPos pos) {
        this.pendingPos = pos.immutable();
        this.pendingTicks = 40;
    }

    @Nullable
    public BlockPos openContainerPos() {
        return this.openHandler != null ? this.openPos : null;
    }

    @Nullable
    public AbstractContainerMenu openHandler() {
        return this.openHandler;
    }

    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            this.pendingPos = null;
            this.openPos = null;
            this.openHandler = null;
            return;
        }
        AbstractContainerMenu current = player.containerMenu;
        boolean containerOpen = current != null && current != player.inventoryMenu;

        if (!containerOpen) {
            this.openHandler = null;
            this.openPos = null;
            if (this.pendingPos != null && --this.pendingTicks <= 0) {
                this.pendingPos = null;
            }
            return;
        }
        if (this.openHandler != current) {
            // A new container screen appeared. Attribute it to the block we just clicked, if any.
            this.openHandler = current;
            this.openPos = this.pendingPos;
            this.pendingPos = null;
        }
        if (this.openPos != null) {
            BlockPos key = StorageManager.canonical(client.level, this.openPos);
            if (StorageManager.get().container(key) != null) {
                StorageManager.get().record(key, snapshot(current));
            }
        }
    }

    /** Item counts in the container part of a screen (player inventory slots excluded). */
    public static Map<Item, Integer> snapshot(AbstractContainerMenu handler) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }
}
