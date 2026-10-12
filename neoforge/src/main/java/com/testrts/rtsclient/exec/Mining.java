package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;

/** Shared bits of the built-in engine's mining. */
public final class Mining {
    /** Fast: start once the block is this close to the middle of the view (keep turning meanwhile). */
    private static final float ROUGH_AIM_DEGREES = 30.0F;

    private Mining() {
    }

    /** Ready to start mining toward {@code face}: exactly aimed (Normal), roughly (Fast), or right away (Fastest). */
    public static boolean aimed(Minecraft client, net.minecraft.world.phys.Vec3 face, boolean exactlyAimed) {
        int speed = RtsConfig.get().miningSpeed;
        return exactlyAimed || speed >= 2 || speed == 1 && Look.roughly(client.player, face, ROUGH_AIM_DEGREES);
    }

    /** Fast and Fastest: no pause before the next block (the game waits 5 ticks after each one it breaks). */
    public static void skipPause(Minecraft client) {
        if (RtsConfig.get().miningSpeed >= 1 && client.gameMode instanceof MultiPlayerGameModeAccessor a) {
            a.rtsclient$setBreakPause(0);
        }
    }
}
