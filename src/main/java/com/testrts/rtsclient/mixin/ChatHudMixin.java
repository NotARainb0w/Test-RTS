package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RTS mode hides the HUD (and chat with it), so copy chat lines into the RTS message area. */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"))
    private void rtsclient$mirror(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        if (RtsController.get().isActive()) {
            Messages.chat(message);
        }
    }
}
