package org.universaltranslator.fabric.mixin;

import net.minecraft.client.render.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures every TextRenderer string at the final draw and width entry points.
 *
 * <p>Both hooks are {@code require = 0}, so a descriptor that no longer matches costs one surface
 * rather than the launch. Each reports through {@link RenderedTextBridge#surface} the first time it
 * fires: without that, a version where the hook silently stopped binding was indistinguishable from
 * a version that never had it, and a report of "text is not translated any more" could not be told
 * apart from "this build never hooked this entry point".
 */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    private static final AtomicBoolean DRAWN_STRING_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean MEASURED_STRING_SURFACE = new AtomicBoolean();

    @ModifyVariable(
            method = {
                    "draw(Ljava/lang/String;FFIZ)I",
                    "drawLayer(Ljava/lang/String;FFIZ)I"
            },
            at = @At("HEAD"),
            argsOnly = true,
            require = 0)
    private String universalTranslator$translateDrawnString(String text) {
        RenderedTextBridge.surface("text-renderer-draw-string", DRAWN_STRING_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Ljava/lang/String;)I",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0)
    private String universalTranslator$translateMeasuredString(String text) {
        RenderedTextBridge.surface("text-renderer-width-string", MEASURED_STRING_SURFACE);
        return RenderedTextBridge.translate(text);
    }
}
