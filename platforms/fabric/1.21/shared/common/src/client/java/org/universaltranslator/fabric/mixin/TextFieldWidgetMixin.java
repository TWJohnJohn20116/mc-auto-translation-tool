package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.TranslationRenderContext;

/**
 * Keeps search boxes, chat fields and configuration fields as local user input.
 *
 * <p>Both hooks are optional ({@code require = 0}). This is the only guard that stops a player's
 * own typing from being translated and rewritten, and the callback it hangs on is a rendering
 * entry point that was renamed once already (26.x calls it {@code extractWidgetRenderState}). A
 * renamed method must degrade instead of aborting the launch; the invariant is kept alive on the
 * other side by {@code FabricLocalTextGuard}, which reads the chat box directly.
 */
@Mixin(TextFieldWidget.class)
abstract class TextFieldWidgetMixin {
    @Inject(
            method = "renderWidget(Lnet/minecraft/client/gui/DrawContext;IIF)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$pushTextInput(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "renderWidget(Lnet/minecraft/client/gui/DrawContext;IIF)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$popTextInput(
            DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
