package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.forge.TranslationRenderContext;

/** Keeps search boxes, chat fields and configuration fields as local user input. */
@Mixin(EditBox.class)
abstract class TextFieldWidgetMixin {
    // Essential: this is the only guard that keeps the player's own half-typed chat line out of the
    // translation pipeline. require = 1 turns a renamed target into a launch failure instead of a
    // silent privacy regression.
    @Inject(
            method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("HEAD"), require = 1)
    private void universalTranslator$pushTextInput(
            GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "renderWidget(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
            at = @At("RETURN"), require = 1)
    private void universalTranslator$popTextInput(
            GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
