package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.TranslationRenderContext;

/**
 * Keeps the local sign editor preview untranslated until the player saves it, on 1.19.3 and 1.19.4.
 *
 * <p>1.19.3 moved {@code render(MatrixStack,int,int,float)} from {@code SignEditScreen} up to
 * {@code AbstractSignEditScreen}, so the shared copy that targets {@code SignEditScreen} itself has
 * no target here: with {@code defaultRequire: 1} it failed the launch instead of losing the guard.
 * Both hooks stay {@code require = 0} for the same reason.
 */
@Mixin(AbstractSignEditScreen.class)
abstract class AbstractSignEditScreenMixin {
    @Inject(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;IIF)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$pushSignInput(
            MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;IIF)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$popSignInput(
            MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
