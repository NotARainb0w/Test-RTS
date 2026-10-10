package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.config.RtsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Creative supply (setting, on by default): in creative mode the builder spawns the blocks it
 * needs into the hotbar, the way the creative inventory does, instead of fetching them from
 * storage or stopping with "missing". Does nothing outside creative mode.
 */
public final class Creative {
    private Creative() {
    }

    public static boolean supplies(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        return RtsConfig.get().creativeSupply && player != null && client.interactionManager != null
                && player.getAbilities().creativeMode;
    }

    /**
     * Put a full stack of {@code item} in the hotbar and select it. Uses an empty hotbar slot, else
     * one holding blocks, else the selected slot.
     *
     * @return false outside creative mode
     */
    public static boolean spawnInHand(MinecraftClient client, Item item) {
        if (!supplies(client)) {
            return false;
        }
        ClientPlayerEntity player = client.player;
        PlayerInventory inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 9 && slot < 0; i++) {
            if (inv.getStack(i).isEmpty()) {
                slot = i;
            }
        }
        for (int i = 0; i < 9 && slot < 0; i++) {
            if (inv.getStack(i).getItem() instanceof BlockItem) {
                slot = i;
            }
        }
        if (slot < 0) {
            slot = inv.selectedSlot;
        }
        ItemStack stack = new ItemStack(item, item.getMaxCount());
        inv.setStack(slot, stack);
        // Hotbar slot i is slot 36 + i of the player's inventory screen handler.
        client.interactionManager.clickCreativeStack(stack, 36 + slot);
        inv.selectedSlot = slot;
        return true;
    }
}
