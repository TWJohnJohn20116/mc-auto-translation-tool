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
 * <p>The {@code prepare} descriptors were checked against the mapped 1.21.10 artifact
 * ({@code TextRenderer#prepare(String;FFIZI)} and {@code prepare(OrderedText;FFIZI)}), so they are
 * the 1.21.6–1.21.10 shapes. Every hook is nevertheless {@code require = 0}, matching
 * {@code platforms/fabric/shared/text-renderer-1.20-1.21}: the mixins json sets
 * {@code defaultRequire: 1}, so one descriptor this layer gets wrong would be a
 * {@code MixinApplyError} on the first frame rather than "world text stays untranslated".
 *
 * <p>If CI or {@code runClient} reports a descriptor mismatch, the fallbacks are:
 * <ul>
 *   <li>OrderedText {@code prepare} — 1.21.11 added a second {@code boolean}, so the later shape is
 *       {@code prepare(Lnet/minecraft/text/OrderedText;FFIZZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;}
 *       (that is what {@code text-renderer/1.21.11} uses).</li>
 *   <li>String {@code prepare} — kept {@code FFIZI} in 1.21.10 <em>and</em> 1.21.11, so a miss here
 *       means this version renamed the method.</li>
 * </ul>
 */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    @ModifyVariable(
            method = "prepare(Ljava/lang/String;FFIZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translatePreparedString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "prepare(Lnet/minecraft/text/OrderedText;FFIZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translatePreparedOrderedText(OrderedText text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true, require = 0)
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
