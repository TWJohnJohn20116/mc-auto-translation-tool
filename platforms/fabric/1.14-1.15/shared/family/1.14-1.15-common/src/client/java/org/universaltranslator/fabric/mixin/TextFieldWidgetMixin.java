package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.TranslationRenderContext;

/** Keeps search boxes, chat fields and configuration fields as local user input. */
@Mixin(TextFieldWidget.class)
abstract class TextFieldWidgetMixin {
    @Inject(method = "renderButton(IIF)V", at = @At("HEAD"))
    private void universalTranslator$pushTextInput(int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(method = "renderButton(IIF)V", at = @At("RETURN"))
    private void universalTranslator$popTextInput(int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
