package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures world-space text such as nameplates, holograms, signs and display entities.
 *
 * <p>These are the 1.21.11 shapes, checked against the mapped 1.21.11 artifact: the OrderedText
 * overload grew a second {@code boolean} ({@code prepare(OrderedText;FFIZZI)}) while the String
 * overload kept one ({@code prepare(String;FFIZI)}). That asymmetry is vanilla, not a copy-paste
 * slip — which is exactly why the four copies of this mixin cannot be collapsed by guesswork.
 * Every hook is {@code require = 0} (the same policy as
 * {@code platforms/fabric/shared/text-renderer-1.20-1.21}): with the mixins json's
 * {@code defaultRequire: 1}, a descriptor that matches nothing would be a {@code MixinApplyError}
 * on the first drawn frame instead of "world text stays untranslated".
 *
 * <p>If CI or {@code runClient} reports a descriptor mismatch, the fallbacks are:
 * <ul>
 *   <li>OrderedText {@code prepare} — drop one {@code Z}:
 *       {@code prepare(Lnet/minecraft/text/OrderedText;FFIZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;}
 *       (the 1.21.6–1.21.10 shape).</li>
 *   <li>String {@code prepare} — add a {@code Z} only if this version made both overloads take two
 *       booleans; the 1.21.10 and 1.21.11 artifacts both say {@code FFIZI} here.</li>
 * </ul>
 */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    // One flag per require = 0 hook: only a hook that actually fired can separate a descriptor that
    // matches nothing from a version that never had that entry point.
    private static final AtomicBoolean universalTranslator$preparedStringSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$preparedOrderedSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$measuredStringSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$measuredTextSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$measuredOrderedSurface =
            new AtomicBoolean();

    @ModifyVariable(
            method = "prepare(Ljava/lang/String;FFIZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translatePreparedString(String text) {
        RenderedTextBridge.surface("text-renderer-prepare-string-1.21.11",
                universalTranslator$preparedStringSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "prepare(Lnet/minecraft/text/OrderedText;FFIZZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translatePreparedOrderedText(OrderedText text) {
        RenderedTextBridge.surface("text-renderer-prepare-ordered-1.21.11",
                universalTranslator$preparedOrderedSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateMeasuredString(String text) {
        RenderedTextBridge.surface("text-renderer-width-string-1.21.11",
                universalTranslator$measuredStringSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/StringVisitable;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private StringVisitable universalTranslator$translateMeasuredText(StringVisitable text) {
        RenderedTextBridge.surface("text-renderer-width-text-1.21.11",
                universalTranslator$measuredTextSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/OrderedText;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateMeasuredOrderedText(OrderedText text) {
        RenderedTextBridge.surface("text-renderer-width-ordered-1.21.11",
                universalTranslator$measuredOrderedSurface);
        return RenderedTextBridge.translate(text);
    }
}
