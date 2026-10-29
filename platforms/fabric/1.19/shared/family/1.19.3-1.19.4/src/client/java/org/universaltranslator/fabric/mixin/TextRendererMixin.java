package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

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
 * {@code require = 0}: this file is the only copy for 1.19.3/1.19.4 and no mapped artifact for
 * those two versions is cached, so a wrong descriptor must degrade to "world text stays
 * un-translated" rather than a {@code MixinApplyError} at startup.
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
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"),
            argsOnly = true, require = 0)
    private String universalTranslator$translateMeasuredString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/StringVisitable;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private StringVisitable universalTranslator$translateMeasuredText(StringVisitable text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/OrderedText;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateMeasuredOrderedText(OrderedText text) {
        return RenderedTextBridge.translate(text);
    }
}
