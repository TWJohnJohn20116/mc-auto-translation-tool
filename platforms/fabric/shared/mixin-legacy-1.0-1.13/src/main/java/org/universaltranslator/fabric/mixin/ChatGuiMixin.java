package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.chat.ChatGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

@Mixin(ChatGui.class)
abstract class ChatGuiMixin {
    @Inject(method = "render(I)V", at = @At("HEAD"), require = 0)
    private void universalTranslator$pushChat(int ticks, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "render(I)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$popChat(int ticks, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
