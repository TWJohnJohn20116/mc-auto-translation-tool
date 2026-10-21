package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.TranslationRenderContext;

/** Keeps the local sign editor preview untranslated until the player saves it. */
@Mixin(SignEditScreen.class)
abstract class SignEditScreenMixin {
    @Inject(method = "render(IIF)V", at = @At("HEAD"))
    private void universalTranslator$pushSignInput(int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(method = "render(IIF)V", at = @At("RETURN"))
    private void universalTranslator$popSignInput(int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
