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

    /**
     * The single-component tooltip overload.
     *
     * <p>{@code GuiGraphics} answers a hover through two entry points, and only the {@code List} one
     * was hooked above. The {@code Component} overload — used by simple hints and by mods that render
     * a one-line tooltip — never passes through the {@code List} version, so those lines stayed
     * untranslated while the class comment claimed tooltips were covered.
     */
    @ModifyVariable(
            method = "renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;II)V",
            at = @At("HEAD"), argsOnly = true, require = 1)
    private Component universalTranslator$translateTooltipComponent(Component text) {
        return RenderedTextBridge.translate(text);
    }
}
