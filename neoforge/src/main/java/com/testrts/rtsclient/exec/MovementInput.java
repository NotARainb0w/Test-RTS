package com.testrts.rtsclient.exec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;

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
        this.up = this.forward;
        this.down = false;
        this.left = false;
        this.right = false;
        this.forwardImpulse = this.forward ? 1.0F : 0.0F;
        this.leftImpulse = 0.0F;
        this.jumping = this.jump;
        this.shiftKeyDown = this.sneak;
        if (slowDown) {
            this.forwardImpulse *= slowDownFactor;
        }
    }

    public void release() {
        this.forward = false;
        this.jump = false;
        this.sneak = false;
    }

    private static final MovementInput INSTANCE = new MovementInput();

    /** Take over the player's movement input. */
    public static MovementInput acquire(LocalPlayer player) {
        if (player.input != INSTANCE) {
            INSTANCE.release();
            player.input = INSTANCE;
        }
        return INSTANCE;
    }

    /** Hand movement back to the keyboard. */
    public static void restore(Minecraft client) {
        INSTANCE.release();
        LocalPlayer player = client.player;
        if (player != null && player.input == INSTANCE) {
            player.input = new KeyboardInput(client.options);
        }
    }
}
