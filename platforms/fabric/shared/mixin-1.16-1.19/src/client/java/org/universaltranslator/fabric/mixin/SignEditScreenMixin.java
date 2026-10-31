package org.universaltranslator.fabric.mixin;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.TranslationRenderContext;

/**
 * Keeps the local sign editor preview untranslated until the player saves it.
 *
 * <p>1.19.3+ does not list this class: the family config for 1.19.3-1.19.4 uses
 * {@code AbstractSignEditScreenMixin} instead, because the screen was renamed and its render method
 * takes a {@code DrawContext} there. This copy is mounted for 1.16-1.19.2 only.
 */
@Mixin(SignEditScreen.class)
abstract class SignEditScreenMixin {
    @Inject(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;IIF)V",
            at = @At("HEAD"))
    private void universalTranslator$pushSignInput(
            MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;IIF)V",
            at = @At("RETURN"))
    private void universalTranslator$popSignInput(
            MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
