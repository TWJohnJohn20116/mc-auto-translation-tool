package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.forge.RenderedTextBridge;

import java.util.List;

/**
 * Translates tooltips, and nothing else.
 *
 * <p>The six {@code drawString}/{@code drawCenteredString} hooks that used to live here were
 * removed: {@code GuiGraphics.drawString} forwards to {@code Font.drawInternal}, which
 * {@link TextRendererMixin} already intercepts, so every GUI string was translated twice per frame
 * - once at the GuiGraphics argument and again at the Font argument - paying two cache keys, two
 * map probes and two heuristic scans for one drawn line. Keeping only the {@code Font} layer also
 * means the measured width and the drawn text always go through the same hook.
 *
 * <p>The tooltip hook stays because tooltip lines are built as components and never pass through
 * the {@code Font} argument path with the untranslated string.
 */
@Mixin(GuiGraphics.class)
abstract class DrawContextMixin {
    @ModifyVariable(
            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private List<Component> universalTranslator$translateTooltipLines(List<Component> lines) {
        return RenderedTextBridge.translateTooltip(lines);
    }
}
