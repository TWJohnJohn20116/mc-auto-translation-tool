package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.screen.inventory.menu.SignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.TranslationRenderContext;

@Mixin(SignEditScreen.class)
abstract class SignEditScreenMixin {
    @Inject(method = "render(IIF)V", at = @At("HEAD"), require = 0)
    private void universalTranslator$pushSignInput(int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(method = "render(IIF)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$popSignInput(int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
