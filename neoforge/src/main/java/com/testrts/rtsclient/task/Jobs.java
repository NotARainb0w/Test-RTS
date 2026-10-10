package com.testrts.rtsclient.task;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/** Small helpers shared by the automation jobs (quarry, trees, farming, smelting, sorting). */
final class Jobs {
    private Jobs() {
    }

    /** Empty slots in the 36 main + hotbar slots. */
    static int emptySlots(Inventory inv) {
        int n = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    static Set<String> pinned() {
        return new HashSet<>(RtsConfig.get().pinned);
    }

    static boolean isPinned(Set<String> pinned, ItemStack stack) {
        return pinned.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    /**
     * Hold {@code item} and right-click {@code face} of {@code target} with it, the way the builder
     * places blocks. Used for seeds and saplings.
     *
     * @return false if the item isn't in the inventory (or couldn't be put in hand)
     */
    static boolean useOn(Minecraft client, BlockPos target, Direction face, Item item) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || !Inventories.hold(client, item)) {
            return false;
        }
        Vec3 hitVec = Vec3.atCenterOf(target).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        Reach.lookAt(player, hitVec);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, face, target, false));
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** The open screen's slot that shows player inventory index {@code index}, or null. */
    @Nullable
    static Slot playerSlot(AbstractContainerMenu handler, int index) {
        for (Slot slot : handler.slots) {
            if (slot.container instanceof Inventory && slot.getContainerSlot() == index) {
                return slot;
            }
        }
        return null;
    }
}
