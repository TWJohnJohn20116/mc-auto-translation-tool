package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes every string, {@code Text} and {@code OrderedText} draw through the bridge.
 *
 * <p>The injections are split by how badly the module needs them:
 *
 * <ul>
 *   <li>The three plain {@code drawText} overloads are the surface every GUI label, button and chat
 *       line goes through; without them nothing in a menu is translated at all. They are
 *       {@code require = 1}, so a descriptor that no longer matches fails the launch loudly instead
 *       of silently shipping a version where translation does nothing.</li>
 *   <li>The centred variants and the tooltip are {@code require = 0}. Their descriptors are the ones
 *       that actually move between generations — 1.21.2 dropped the {@code Optional} from
 *       {@code drawTooltip}, for example — and losing them costs one surface, not the feature.</li>
 * </ul>
 *
 * <p>Every optional hook reports itself through {@link RenderedTextBridge#surface} the first time it
 * fires, so a debug log now says which surfaces this build really reached. A version where a hook
 * silently stopped binding used to be indistinguishable from one that was never supported.
 *
 * <p>Descriptor table for the generations that share this file — a version missing from this list
 * keeps its own copy rather than sharing this one, and adding a generation means checking the
 * descriptors before it is given a layer:
 *
 * <ul>
 *   <li>1.20.1: {@code drawText*…I}, {@code drawTooltip} takes a {@code java.util.Optional}.</li>
 *   <li>1.21.0-1.21.1: same descriptors as 1.20.1.</li>
 *   <li>1.21.2-1.21.5: {@code drawText*…I}; {@code drawTooltip} lost the {@code Optional}, so that
 *       hook is expected to miss here and stays {@code require = 0}.</li>
 * </ul>
 *
 * <p>{@code defaultRequire: 1} stays in the mixin config, so the {@code require = 0} on an optional
 * hook is an explicit, local decision rather than an inherited one.
 */
@Mixin(DrawContext.class)
abstract class DrawContextMixin {
    private static final AtomicBoolean TOOLTIP_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean DRAW_STRING_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean DRAW_TEXT_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean DRAW_ORDERED_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean CENTERED_STRING_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean CENTERED_TEXT_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean CENTERED_ORDERED_SURFACE = new AtomicBoolean();

    @ModifyVariable(
            method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private List<Text> universalTranslator$translateTooltipLines(List<Text> lines) {
        RenderedTextBridge.surface("draw-tooltip", TOOLTIP_SURFACE);
        return RenderedTextBridge.translateTooltip(lines);
    }

    @ModifyVariable(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 1)
    private String universalTranslator$translateString(String text) {
        RenderedTextBridge.surface("draw-text-string", DRAW_STRING_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 1)
    private Text universalTranslator$translateText(Text text) {
        RenderedTextBridge.surface("draw-text-component", DRAW_TEXT_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateOrderedText(OrderedText text) {
        RenderedTextBridge.surface("draw-text-ordered", DRAW_ORDERED_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateCenteredString(String text) {
        RenderedTextBridge.surface("draw-centered-string", CENTERED_STRING_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Text universalTranslator$translateCenteredText(Text text) {
        RenderedTextBridge.surface("draw-centered-component", CENTERED_TEXT_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateCenteredOrderedText(OrderedText text) {
        RenderedTextBridge.surface("draw-centered-ordered", CENTERED_ORDERED_SURFACE);
        return RenderedTextBridge.translate(text);
    }
}
