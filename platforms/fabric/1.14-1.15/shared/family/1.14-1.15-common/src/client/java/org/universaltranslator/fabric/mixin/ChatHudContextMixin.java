package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

@Mixin(ChatHud.class)
abstract class ChatHudContextMixin {
    @Inject(method = "draw(I)V", at = @At("HEAD"))
    private void universalTranslator$enterChat(int currentTick, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "draw(I)V", at = @At("RETURN"))
    private void universalTranslator$leaveChat(int currentTick, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
