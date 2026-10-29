package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.TranslationRenderContext;

@Mixin(ChatComponent.class)
abstract class ChatHudContextMixin {
    // Essential: chat is the main surface the mod translates, and the colour/kind decision below
    // only ever sees TextKind.CHAT if these two hooks exist. require = 1 makes a renamed target a
    // launch failure instead of a silently untranslated chat box.
    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void universalTranslator$enterChat(
            GuiGraphics context, int currentTick, int mouseX, int mouseY, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void universalTranslator$leaveChat(
            GuiGraphics context, int currentTick, int mouseX, int mouseY, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
