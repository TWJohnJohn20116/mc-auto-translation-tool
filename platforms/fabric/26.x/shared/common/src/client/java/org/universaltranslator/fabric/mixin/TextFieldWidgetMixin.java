package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
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
 * entry point Mojang has already renamed once ({@code renderWidget} before 26.x). A renamed method
 * must degrade instead of aborting the launch; the invariant is kept alive on the other side by
 * {@code FabricLocalTextGuard}, which reads the chat box directly.
 */
@Mixin(EditBox.class)
abstract class TextFieldWidgetMixin {
    @Inject(
            method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$pushTextInput(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$popTextInput(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
