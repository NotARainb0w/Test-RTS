package com.testrts.rtsclient.storage;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.math.BlockPos;
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
    private ScreenHandler openHandler;

    public static ContainerTracker get() {
        return INSTANCE;
    }

    /** Call right before sending an interact packet for a block that may open a container. */
    public void expect(BlockPos pos) {
        this.pendingPos = pos.toImmutable();
        this.pendingTicks = 40;
    }

    @Nullable
    public BlockPos openContainerPos() {
        return this.openHandler != null ? this.openPos : null;
    }

    @Nullable
    public ScreenHandler openHandler() {
        return this.openHandler;
    }

    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            this.pendingPos = null;
            this.openPos = null;
            this.openHandler = null;
            return;
        }
        ScreenHandler current = player.currentScreenHandler;
        boolean containerOpen = current != null && current != player.playerScreenHandler;

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
            BlockPos key = StorageManager.canonical(client.world, this.openPos);
            if (StorageManager.get().container(key) != null) {
                StorageManager.get().record(key, snapshot(current));
            }
        }
    }

    /** Item counts in the container part of a screen (player inventory slots excluded). */
    public static Map<Item, Integer> snapshot(ScreenHandler handler) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) {
                continue;
            }
            ItemStack stack = slot.getStack();
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }
}
