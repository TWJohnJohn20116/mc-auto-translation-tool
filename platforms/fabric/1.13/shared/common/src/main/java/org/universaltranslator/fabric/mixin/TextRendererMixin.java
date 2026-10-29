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
            // drawWithShadow(String,float,float,int) is deliberately not listed (P2-051): it is a
            // one-line delegate to the five-argument draw(String,float,float,int,boolean) below, so
            // listing both sent every shadowed string — most of the HUD, chat and tooltip text —
            // through RenderedTextBridge twice per drawn line, doubling the cost of the 1.13 text
            // pipeline against the 1.0–1.12 one (platforms/fabric/shared/text-renderer-legacy lists
            // only the terminal methods). The four-argument draw is a sibling implementation rather
            // than a delegate, which is why it stays.
            method = {
                    "draw(Ljava/lang/String;FFI)I",
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
