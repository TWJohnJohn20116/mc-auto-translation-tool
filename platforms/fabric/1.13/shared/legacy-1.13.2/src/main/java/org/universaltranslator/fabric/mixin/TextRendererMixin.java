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
            //
            // drawWithShadow(String,float,float,int) is deliberately not listed (P2-051): it is a
            // one-line delegate to the five-argument draw(String,float,float,int,boolean) below, so
            // listing both sent every shadowed string — most of the HUD, chat and tooltip text —
            // through RenderedTextBridge twice per drawn line. The four-argument draw is a sibling
            // implementation rather than a delegate, which is why it has to stay. Anything 1.13.2
            // draws with a shadow still reaches this list through the five-argument method.
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
