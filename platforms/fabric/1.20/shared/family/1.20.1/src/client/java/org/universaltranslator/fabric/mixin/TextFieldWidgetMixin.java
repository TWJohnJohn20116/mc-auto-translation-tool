package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.RenderedTextBridge;
import org.universaltranslator.fabric.TranslationRenderContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps search boxes, chat fields and configuration fields as local user input.
 *
 * <p>1.20.3 renamed {@code renderButton} to {@code renderWidget}; both descriptors are listed because
 * one file serves 1.20-1.20.6 and {@code require = 0} means "at least one of them has to match".
 *
 * <p>Both injections report through {@code RenderedTextBridge.surface} the first time they fire:
 * {@code require = 0} was completely silent, so a rename like that one left the surface dead with
 * nothing in the log to say so.
 */
@Mixin(TextFieldWidget.class)
abstract class TextFieldWidgetMixin {
    private static final AtomicBoolean universalTranslator$textInputPushSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$textInputPopSurface = new AtomicBoolean();

    @Inject(
            method = {
                    "renderButton(Lnet/minecraft/client/gui/DrawContext;IIF)V",
                    "renderWidget(Lnet/minecraft/client/gui/DrawContext;IIF)V"
            },
            at = @At("HEAD"), require = 0)
    private void universalTranslator$pushTextInput(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        RenderedTextBridge.surface("text-field-input-push", universalTranslator$textInputPushSurface);
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = {
                    "renderButton(Lnet/minecraft/client/gui/DrawContext;IIF)V",
                    "renderWidget(Lnet/minecraft/client/gui/DrawContext;IIF)V"
            },
            at = @At("RETURN"), require = 0)
    private void universalTranslator$popTextInput(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        RenderedTextBridge.surface("text-field-input-pop", universalTranslator$textInputPopSurface);
        TranslationRenderContext.popTextInput();
    }
}
