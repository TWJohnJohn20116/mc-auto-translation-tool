package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.forge.ForgeLocalTextGuard;
import org.universaltranslator.forge.TranslationRenderContext;

/** Keeps search boxes, chat fields and configuration fields as local user input. */
@Mixin(remap = false, value = EditBox.class)
abstract class TextFieldWidgetMixin {
    @Inject(
            method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"))
    private void universalTranslator$pushTextInput(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        // Report the instance instead of letting the guard reflect over ChatScreen's fields on
        // every rendered string: the mixin already has the widget the player is typing into.
        ForgeLocalTextGuard.trackRenderedInput((EditBox) (Object) this);
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
