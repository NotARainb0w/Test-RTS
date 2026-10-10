package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.config.RtsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Creative supply (setting, on by default): in creative mode the builder spawns the blocks it
 * needs into the hotbar, the way the creative inventory does, instead of fetching them from
 * storage or stopping with "missing". Does nothing outside creative mode.
 */
public final class Creative {
    private Creative() {
    }

    public static boolean supplies(Minecraft client) {
        LocalPlayer player = client.player;
        return RtsConfig.get().creativeSupply && player != null && client.gameMode != null
                && player.getAbilities().instabuild;
    }

    /**
     * Put a full stack of {@code item} in the hotbar and select it. Uses an empty hotbar slot, else
     * one holding blocks, else the selected slot.
     *
     * @return false outside creative mode
     */
    public static boolean spawnInHand(Minecraft client, Item item) {
        if (!supplies(client)) {
            return false;
        }
        LocalPlayer player = client.player;
        Inventory inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 9 && slot < 0; i++) {
            if (inv.getItem(i).isEmpty()) {
                slot = i;
            }
        }
        for (int i = 0; i < 9 && slot < 0; i++) {
            if (inv.getItem(i).getItem() instanceof BlockItem) {
                slot = i;
            }
        }
        if (slot < 0) {
            slot = inv.selected;
        }
        ItemStack stack = new ItemStack(item, item.getDefaultMaxStackSize());
        inv.setItem(slot, stack);
        // Hotbar slot i is slot 36 + i of the player's inventory menu.
        client.gameMode.handleCreativeModeItemAdd(stack, 36 + slot);
        inv.selected = slot;
        return true;
    }
}
