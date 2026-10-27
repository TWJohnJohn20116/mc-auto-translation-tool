package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

/** Captures every TextRenderer string at the final draw and width entry points. */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    @ModifyVariable(
            // The four-argument draw(String,float,float,int) exists in 1.13.2 as well; it went missing
            // when this file was rewritten for legacy-yarn mappings, so text drawn through it stayed
            // untranslated with no log line (require = 0 and defaultRequire 0 keep a miss silent).
            method = {
                    "draw(Ljava/lang/String;FFI)I",
                    "draw(Ljava/lang/String;FFIZ)I",
                    "drawWithShadow(Ljava/lang/String;FFI)I",
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
