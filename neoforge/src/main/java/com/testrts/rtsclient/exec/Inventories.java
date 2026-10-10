package com.testrts.rtsclient.exec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Puts the right item in the player's hand, pulling it from the main inventory if needed. */
public final class Inventories {
    private Inventories() {
    }

    /** @return true if {@code item} is now in the main hand. */
    public static boolean hold(Minecraft client, Item item) {
        LocalPlayer player = client.player;
        if (player == null) {
            return false;
        }
        Inventory inv = player.getInventory();
        if (inv.getSelected().is(item)) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
                select(client, i);
                return true;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(item)) {
                return swapIntoHand(client, i);
            }
        }
        // Creative supply: conjure it up.
        return Creative.spawnInHand(client, item);
    }

    /** Hold the fastest tool for this block (if any tool helps at all). */
    public static void holdBestTool(Minecraft client, BlockState state) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        Inventory inv = player.getInventory();
        float current = inv.getSelected().getDestroySpeed(state);
        int bestSlot = -1;
        float best = Math.max(1.0F, current);
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
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

    private static void select(Minecraft client, int hotbarSlot) {
        LocalPlayer player = client.player;
        // The interaction manager syncs the selected slot to the server before every use/attack.
        player.getInventory().selected = hotbarSlot;
    }

    /** Swap a main-inventory stack (index 9..35) with the selected hotbar slot. */
    private static boolean swapIntoHand(Minecraft client, int inventoryIndex) {
        LocalPlayer player = client.player;
        if (client.gameMode == null || player.containerMenu != player.inventoryMenu) {
            return false;
        }
        // In the player screen handler, main inventory index i (9..35) is slot id i.
        client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, inventoryIndex,
                player.getInventory().selected, ClickType.SWAP, player);
        return true;
    }

    public static int count(Minecraft client, Item item) {
        return client.player == null ? 0 : client.player.getInventory().countItem(item);
    }
}
