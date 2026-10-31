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
 * <p>The two {@code drawInternal} descriptors below are the only ones in this tree that could not
 * be checked against a mapped artifact: 1.21.2–1.21.5 are not cached locally. What the cached
 * generations do say is that the trailing {@code boolean} is not a property of the OrderedText
 * overload but of the version — 1.21.1 has {@code …TextLayerType;IIZ)I} for String and
 * {@code …TextLayerType;II)I} for OrderedText, while 1.21.10 and 1.21.11 have replaced
 * {@code drawInternal} with {@code prepare} altogether.
 *
 * <p>So the descriptors are left exactly as they were and made optional instead: a descriptor that
 * matches nothing then means "this generation falls back to un-translated world text", never a
 * {@code MixinApplyError} at startup (the mixins json sets {@code defaultRequire: 1}, so a required
 * and unmatched injection fails the whole client). If CI or {@code runClient} reports a descriptor
 * mismatch here, the fallbacks are:
 * <ul>
 *   <li>{@code universalTranslator$translatePreparedOrderedText} — drop the last {@code Z}:
 *       {@code drawInternal(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)I}
 *       (the 1.21.1 shape).</li>
 *   <li>{@code universalTranslator$translatePreparedString} — the String overload kept the trailing
 *       {@code Z} in both 1.21.1 and the 1.21.6+ {@code prepare} generation, so it is the pair that
 *       is most likely already right; if it still misses, this generation renamed the method and
 *       the hook belongs in a new layer.</li>
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
            method = "drawInternal(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;IIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translatePreparedString(String text) {
        RenderedTextBridge.surface("text-renderer-draw-string-1.21.2",
                universalTranslator$preparedStringSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawInternal(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;IIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translatePreparedOrderedText(OrderedText text) {
        RenderedTextBridge.surface("text-renderer-draw-ordered-1.21.2",
                universalTranslator$preparedOrderedSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateMeasuredString(String text) {
        RenderedTextBridge.surface("text-renderer-width-string-1.21.2",
                universalTranslator$measuredStringSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/StringVisitable;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private StringVisitable universalTranslator$translateMeasuredText(StringVisitable text) {
        RenderedTextBridge.surface("text-renderer-width-text-1.21.2",
                universalTranslator$measuredTextSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/OrderedText;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateMeasuredOrderedText(OrderedText text) {
        RenderedTextBridge.surface("text-renderer-width-ordered-1.21.2",
                universalTranslator$measuredOrderedSurface);
        return RenderedTextBridge.translate(text);
    }
}
