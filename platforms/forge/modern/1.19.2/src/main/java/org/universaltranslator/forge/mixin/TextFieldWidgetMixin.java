package org.universaltranslator.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.forge.TranslationRenderContext;

/** Keeps search boxes, chat fields and configuration fields as local user input. */
@Mixin(EditBox.class)
abstract class TextFieldWidgetMixin {
    @Inject(
            method = "renderButton(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
            at = @At("HEAD"))
    private void universalTranslator$pushTextInput(
            PoseStack context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.pushTextInput();
    }

    @Inject(
            method = "renderButton(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V",
            at = @At("RETURN"))
    private void universalTranslator$popTextInput(
            PoseStack context, int mouseX, int mouseY, float delta, CallbackInfo callback) {
        TranslationRenderContext.popTextInput();
    }
}
