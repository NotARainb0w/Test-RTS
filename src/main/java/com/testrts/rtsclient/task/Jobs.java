package com.testrts.rtsclient.task;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Inventories;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/** Small helpers shared by the automation jobs (quarry, trees, farming, smelting, sorting). */
final class Jobs {
    private Jobs() {
    }

    /** Empty slots in the 36 main + hotbar slots. */
    static int emptySlots(PlayerInventory inv) {
        int n = 0;
        for (int i = 0; i < inv.main.size(); i++) {
            if (inv.main.get(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    static Set<String> pinned() {
        return new HashSet<>(RtsConfig.get().pinned);
    }

    static boolean isPinned(Set<String> pinned, ItemStack stack) {
        return pinned.contains(Registries.ITEM.getId(stack.getItem()).toString());
    }

    /**
     * Hold {@code item} and right-click {@code face} of {@code target} with it, the way the builder
     * places blocks. Used for seeds and saplings.
     *
     * @return false if the item isn't in the inventory (or couldn't be put in hand)
     */
    static boolean useOn(MinecraftClient client, BlockPos target, Direction face, Item item) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null || !Inventories.hold(client, item)) {
            return false;
        }
        Vec3d hitVec = Vec3d.ofCenter(target).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, target, false);
        // Turn toward it first (no snapping), then click, as long as the item is still in hand.
        com.testrts.rtsclient.exec.Look.thenDo(client, hitVec, () -> {
            if (client.interactionManager != null && client.player == player && player.getMainHandStack().isOf(item)) {
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                player.swingHand(Hand.MAIN_HAND);
            }
        });
        return true;
    }

    /** The open screen's slot that shows player inventory index {@code index}, or null. */
    @Nullable
    static Slot playerSlot(ScreenHandler handler, int index) {
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() == index) {
                return slot;
            }
        }
        return null;
    }
}
