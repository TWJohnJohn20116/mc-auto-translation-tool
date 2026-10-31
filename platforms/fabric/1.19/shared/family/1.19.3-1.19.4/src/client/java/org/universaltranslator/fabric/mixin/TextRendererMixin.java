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
 * <p>Both draw overloads are hooked. Hooking only the String one left every line that renders
 * through {@code OrderedText} (chat, scoreboard, entity names — most of the screen) drawn from the
 * original while its width was measured from the translation, so centred text drifted and
 * tooltips wrapped at the wrong place.
 *
 * <p>The String hook keeps its bare method name on purpose: it is the form that has been binding
 * since 1.19.3, and a bare name lets Mixin pick the overload whose arguments match the handler.
 * The OrderedText hook cannot rely on that (its handler takes the same carrier type as the
 * StringVisitable width hook on some builds), so it is pinned to a descriptor and marked
 * {@code require = 0}.
 *
 * <p>This layer serves 1.19.3 and 1.19.4, and {@code drawInternal} is not the same shape in both:
 * 1.19.4 carries {@code TextRenderer$TextLayerType} before the background colour and light
 * parameters, 1.19.3 does not. Both descriptors are listed, each {@code require = 0}, so the version
 * that owns the other shape simply skips the pair — 1.19.3 used to have only the 1.19.4 descriptor
 * and therefore translated nothing that renders through {@code OrderedText} while its width came
 * from the translated text.
 *
 * <p>If CI or {@code runClient} reports a descriptor mismatch on the OrderedText hook, the
 * fallbacks are, in order:
 * <ul>
 *   <li>add the trailing {@code Z} the 1.21.x String overload carries:
 *       {@code drawInternal(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;IIZ)I};</li>
 *   <li>pre-1.19.3 text-renderer shapes (no {@code TextLayerType}) belong to
 *       {@code legacy/shared/family/1.16.5-1.19.2}, not here.</li>
 * </ul>
 */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    // One flag per require = 0 hook: only a hook that actually fired can separate a drifted
    // descriptor from a version that never had that entry point.
    private static final AtomicBoolean universalTranslator$orderedTextSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$orderedTextNoLayerSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$measuredStringSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$measuredTextSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$measuredOrderedSurface =
            new AtomicBoolean();

    @ModifyVariable(
            method = "drawInternal",
            at = @At("HEAD"), argsOnly = true)
    private String universalTranslator$translatePreparedString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawInternal(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translatePreparedOrderedText(OrderedText text) {
        RenderedTextBridge.surface("text-renderer-ordered-layer-1.19.4",
                universalTranslator$orderedTextSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawInternal(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZII)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translatePreparedOrderedTextNoLayer(OrderedText text) {
        RenderedTextBridge.surface("text-renderer-ordered-1.19.3",
                universalTranslator$orderedTextNoLayerSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"),
            argsOnly = true, require = 0)
    private String universalTranslator$translateMeasuredString(String text) {
        RenderedTextBridge.surface("text-renderer-width-string",
                universalTranslator$measuredStringSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/StringVisitable;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private StringVisitable universalTranslator$translateMeasuredText(StringVisitable text) {
        RenderedTextBridge.surface("text-renderer-width-text",
                universalTranslator$measuredTextSurface);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/OrderedText;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateMeasuredOrderedText(OrderedText text) {
        RenderedTextBridge.surface("text-renderer-width-ordered",
                universalTranslator$measuredOrderedSurface);
        return RenderedTextBridge.translate(text);
    }
}
