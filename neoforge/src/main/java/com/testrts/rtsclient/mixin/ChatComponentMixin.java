package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RTS mode hides the HUD (and chat with it), so copy chat lines into the RTS message area. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"))
    private void rtsclient$mirror(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        if (RtsController.get().isActive()) {
            Messages.chat(message);
        }
    }
}
