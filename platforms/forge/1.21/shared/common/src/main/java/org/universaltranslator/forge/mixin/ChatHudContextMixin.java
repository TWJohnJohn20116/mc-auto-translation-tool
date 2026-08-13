package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.TranslationRenderContext;

@Mixin(value = ChatComponent.class, remap = false)
abstract class ChatHudContextMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void universalTranslator$enterChat(
            GuiGraphics context,
            Font font,
            int currentTick,
            int mouseX,
            int mouseY,
            boolean chatFocused,
            boolean hidden,
            CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void universalTranslator$leaveChat(
            GuiGraphics context,
            Font font,
            int currentTick,
            int mouseX,
            int mouseY,
            boolean chatFocused,
            boolean hidden,
            CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
