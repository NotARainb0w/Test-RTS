package com.testrts.rtsclient.exec;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Movement "keys" pressed by the built-in engine. Installed as the player's input while the
 * engine walks, the same way vanilla's keyboard input drives the player.
 */
public final class MovementInput extends Input {
    public boolean forward;
    public boolean jump;
    public boolean sneak;

    @Override
    public void tick(boolean slowDown, float slowDownFactor) {
        this.pressingForward = this.forward;
        this.pressingBack = false;
        this.pressingLeft = false;
        this.pressingRight = false;
        this.movementForward = this.forward ? 1.0F : 0.0F;
        this.movementSideways = 0.0F;
        this.jumping = this.jump;
        this.sneaking = this.sneak;
        if (slowDown) {
            this.movementForward *= slowDownFactor;
        }
    }

    public void release() {
        this.forward = false;
        this.jump = false;
        this.sneak = false;
    }

    private static final MovementInput INSTANCE = new MovementInput();

    /** Take over the player's movement input. */
    public static MovementInput acquire(ClientPlayerEntity player) {
        if (player.input != INSTANCE) {
            INSTANCE.release();
            player.input = INSTANCE;
        }
        return INSTANCE;
    }

    /** Hand movement back to the keyboard. */
    public static void restore(MinecraftClient client) {
        INSTANCE.release();
        ClientPlayerEntity player = client.player;
        if (player != null && player.input == INSTANCE) {
            player.input = new KeyboardInput(client.options);
        }
    }
}
