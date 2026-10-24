package org.universaltranslator.fabric.mixin;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.TranslationRenderContext;

/**
 * Keeps search boxes, chat fields and configuration fields as local user input.
 *
 * <p>Both injections are {@code require = 0} and must stay paired: {@code renderButton} is the only
 * method they touch, so either both apply or neither does. If a future generation renames it, the
 * result is "text fields are translated again" rather than a crash at startup, and the push/pop pair
 * cannot end up applying one half only.
 */
@Mixin(TextFieldWidget.class)
abstract class TextFieldWidgetMixin {
    @Inject(
            method = "renderButton(Lnet/minecraft/client/util/math/MatrixStack;IIF)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$pushTextInput(
            MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "renderButton(Lnet/minecraft/client/util/math/MatrixStack;IIF)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$popTextInput(
            MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
