package com.testrts.rtsclient.exec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

/** Right-clicks that place a block. */
public final class Clicks {
    private Clicks() {
    }

    /**
     * Right-click {@code hit} to place what's in the main hand, sneaking for that one click, the way a
     * player places a block against a chest. Without it, clicking a block that opens a screen (chests,
     * crafting tables, modded machines...) opens the screen instead and leaves us stuck in it.
     */
    public static void place(Minecraft client, LocalPlayer player, BlockHitResult hit) {
        if (client.gameMode == null) {
            return;
        }
        boolean wasSneaking = player.input != null && player.input.shiftKeyDown;
        if (!wasSneaking) {
            // The server checks the sneak state it was told about; the client its own input.
            player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
            if (player.input != null) {
                player.input.shiftKeyDown = true;
            }
        }
        try {
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            // The hologram drops the block now instead of at its next refresh.
            com.testrts.rtsclient.render.WorldOverlay.poke();
        } finally {
            if (!wasSneaking) {
                if (player.input != null) {
                    player.input.shiftKeyDown = false;
                }
                player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            }
        }
    }
}
