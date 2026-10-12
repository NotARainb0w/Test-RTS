package com.testrts.rtsclient.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's pause between broken blocks, so fast mining can skip it. */
@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
    @Accessor("blockBreakingCooldown")
    void rtsclient$setBreakPause(int ticks);
}
