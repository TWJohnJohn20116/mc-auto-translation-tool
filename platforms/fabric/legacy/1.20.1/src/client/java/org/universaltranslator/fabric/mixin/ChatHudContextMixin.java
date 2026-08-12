package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

@Mixin(ChatHud.class)
abstract class ChatHudContextMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;III)V", at = @At("HEAD"))
    private void universalTranslator$enterChat(
            DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;III)V", at = @At("RETURN"))
    private void universalTranslator$leaveChat(
            DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
