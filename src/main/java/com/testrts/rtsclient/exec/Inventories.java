package com.testrts.rtsclient.exec;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

/** Puts the right item in the player's hand, pulling it from the main inventory if needed. */
public final class Inventories {
    private Inventories() {
    }

    /** @return true if {@code item} is now in the main hand. */
    public static boolean hold(MinecraftClient client, Item item) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        if (inv.getMainHandStack().isOf(item)) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) {
                select(client, i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(item)) {
                return swapIntoHand(client, i);
            }
        }
        // Creative supply: conjure it up.
        return Creative.spawnInHand(client, item);
    }

    /** Hold the fastest tool for this block (if any tool helps at all). */
    public static void holdBestTool(MinecraftClient client, BlockState state) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        float current = inv.getMainHandStack().getMiningSpeedMultiplier(state);
        int bestSlot = -1;
        float best = Math.max(1.0F, current);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > best + 0.01F) {
                best = speed;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return;
        }
        if (bestSlot < 9) {
            select(client, bestSlot);
        } else {
            swapIntoHand(client, bestSlot);
        }
    }

    private static void select(MinecraftClient client, int hotbarSlot) {
        ClientPlayerEntity player = client.player;
        // The interaction manager syncs the selected slot to the server before every use/attack.
        player.getInventory().selectedSlot = hotbarSlot;
    }

    /** Swap a main-inventory stack (index 9..35) with the selected hotbar slot. */
    private static boolean swapIntoHand(MinecraftClient client, int inventoryIndex) {
        ClientPlayerEntity player = client.player;
        if (client.interactionManager == null || player.currentScreenHandler != player.playerScreenHandler) {
            return false;
        }
        select(client, handSlot(player.getInventory()));
        // In the player screen handler, main inventory index i (9..35) is slot id i.
        client.interactionManager.clickSlot(player.playerScreenHandler.syncId, inventoryIndex,
                player.getInventory().selectedSlot, SlotActionType.SWAP, player);
        return true;
    }

    /**
     * The hotbar slot to swap something from the main inventory into. Not the first or last one:
     * Baritone's inventory management (allowInventory, which RTS orders turn on) keeps the best pickaxe
     * in the first and throwaway blocks in the last, and puts them back every tick. Swapping our block
     * into those had the two fighting over the slot, the hand flicking between block and pickaxe.
     */
    private static int handSlot(PlayerInventory inv) {
        int current = inv.selectedSlot;
        if (current != 0 && current != 8) {
            return current;
        }
        for (int i = 1; i < 8; i++) {
            if (inv.getStack(i).isEmpty()) {
                return i;
            }
        }
        return 1;
    }

    public static int count(MinecraftClient client, Item item) {
        return client.player == null ? 0 : client.player.getInventory().count(item);
    }
}
