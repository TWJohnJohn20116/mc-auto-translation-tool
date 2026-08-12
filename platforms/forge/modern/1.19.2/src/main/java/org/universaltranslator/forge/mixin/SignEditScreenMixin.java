package org.universaltranslator.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.forge.TranslationRenderContext;

/** Keeps the local sign editor preview untranslated until the player saves it. */
@Mixin(SignEditScreen.class)
abstract class SignEditScreenMixin {
    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
            at = @At("HEAD"))
    private void universalTranslator$pushSignInput(
            PoseStack context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
            at = @At("RETURN"))
    private void universalTranslator$popSignInput(
            PoseStack context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
