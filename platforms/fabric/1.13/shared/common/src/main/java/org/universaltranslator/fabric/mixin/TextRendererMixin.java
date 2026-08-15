package org.universaltranslator.fabric.mixin;

import net.minecraft.client.render.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

/** Captures every TextRenderer string at the final draw and width entry points. */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    @ModifyVariable(
            method = {
                    "draw(Ljava/lang/String;FFI)I",
                    "drawWithShadow(Ljava/lang/String;FFI)I",
                    "draw(Ljava/lang/String;FFIZ)I",
                    "drawLayer(Ljava/lang/String;FFIZ)I"
            },
            at = @At("HEAD"),
            argsOnly = true,
            require = 0)
    private String universalTranslator$translateDrawnString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = {
                    "getWidth(Ljava/lang/String;)I",
                    "getStringWidth(Ljava/lang/String;)I"
            },
            at = @At("HEAD"),
            argsOnly = true,
            require = 0)
    private String universalTranslator$translateMeasuredString(String text) {
        return RenderedTextBridge.translate(text);
    }
}
