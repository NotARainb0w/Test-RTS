package com.testrts.rtsclient.exec;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;

/** Right-clicks that place a block. */
public final class Clicks {
    private Clicks() {
    }

    /**
     * Right-click {@code hit} to place what's in the main hand, sneaking for that one click, the way a
     * player places a block against a chest. Without it, clicking a block that opens a screen (chests,
     * crafting tables, modded machines...) opens the screen instead and leaves us stuck in it.
     */
    public static void place(MinecraftClient client, ClientPlayerEntity player, BlockHitResult hit) {
        if (client.interactionManager == null) {
            return;
        }
        boolean wasSneaking = player.input != null && player.input.sneaking;
        if (!wasSneaking) {
            // The server checks the sneak state it was told about; the client its own input.
            player.networkHandler.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
            if (player.input != null) {
                player.input.sneaking = true;
            }
        }
        try {
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
        } finally {
            if (!wasSneaking) {
                if (player.input != null) {
                    player.input.sneaking = false;
                }
                player.networkHandler.sendPacket(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
            }
        }
    }
}
