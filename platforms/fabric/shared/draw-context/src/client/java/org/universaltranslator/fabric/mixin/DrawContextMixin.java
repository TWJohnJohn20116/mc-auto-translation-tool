package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

import java.util.List;

/**
 * Routes every string, {@code Text} and {@code OrderedText} draw through the bridge.
 *
 * <p>Every injection point is {@code require = 0}. This class is shared by 1.18 through 26.x and the
 * descriptors it names do change between them — 1.21.2 dropped the {@code Optional} from
 * {@code drawTooltip}, for example — so the choice is between "this version draws untranslated text"
 * and "the game does not start". The legacy layer (`shared/text-renderer-legacy`) already made this
 * choice for 1.8–1.12; {@code defaultRequire: 1} in the mixin config made the modern layer the only
 * place where a descriptor drift became a crash on startup.
 */
@Mixin(DrawContext.class)
abstract class DrawContextMixin {
    @ModifyVariable(
            method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private List<Text> universalTranslator$translateTooltipLines(List<Text> lines) {
        return RenderedTextBridge.translateTooltip(lines);
    }

    @ModifyVariable(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Text universalTranslator$translateText(Text text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateOrderedText(OrderedText text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translateCenteredString(String text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Text universalTranslator$translateCenteredText(Text text) {
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateCenteredOrderedText(OrderedText text) {
        return RenderedTextBridge.translate(text);
    }
}
