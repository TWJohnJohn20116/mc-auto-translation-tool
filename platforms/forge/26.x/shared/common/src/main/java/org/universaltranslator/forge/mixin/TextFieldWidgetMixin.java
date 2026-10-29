package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.forge.TranslationRenderContext;

/** Keeps search boxes, chat fields and configuration fields as local user input. */
@Mixin(remap = false, value = EditBox.class)
abstract class TextFieldWidgetMixin {
    @Inject(
            method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"))
    private void universalTranslator$pushTextInput(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("RETURN"))
    private void universalTranslator$popTextInput(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
