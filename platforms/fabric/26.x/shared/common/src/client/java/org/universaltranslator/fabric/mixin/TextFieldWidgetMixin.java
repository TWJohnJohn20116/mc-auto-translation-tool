package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
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
 * <p>Both hooks are optional ({@code require = 0}). This is the only guard that stops a player's
 * own typing from being translated and rewritten, and the callback it hangs on is a rendering
 * entry point Mojang has already renamed once ({@code renderWidget} before 26.x). A renamed method
 * must degrade instead of aborting the launch; the invariant is kept alive on the other side by
 * {@code FabricLocalTextGuard}, which reads the chat box directly.
 */
@Mixin(EditBox.class)
abstract class TextFieldWidgetMixin {
    // One flag per require = 0 hook: only a hook that actually fired can separate a renamed method
    // from a version that never had that entry point.
    private static final AtomicBoolean universalTranslator$pushTextInputSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$popTextInputSurface = new AtomicBoolean();

    @Inject(
            method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$pushTextInput(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        RenderedTextBridge.surface("text-field-input-push-26.x",
                universalTranslator$pushTextInputSurface);
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "extractWidgetRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$popTextInput(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        RenderedTextBridge.surface("text-field-input-pop-26.x",
                universalTranslator$popTextInputSurface);
        TranslationRenderContext.popTextInput();
    }
}
