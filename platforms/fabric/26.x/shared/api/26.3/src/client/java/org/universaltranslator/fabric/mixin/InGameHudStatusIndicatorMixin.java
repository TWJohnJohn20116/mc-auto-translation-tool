package org.universaltranslator.fabric.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects the translation status indicator into the HUD.
 *
 * <p>Everything it draws comes from {@code HudIndicatorSettings}: the corner, size, margin, colour,
 * what appears inside the square, and when it is drawn at all. Only the optional label needs a lang
 * entry; the rest is rectangles and literal text.
 *
 * <p>The painting itself lives in {@code HudIndicatorPainter} (shared/common): the 26.3 copy used to
 * be byte-identical to 26.2 apart from its javadoc, so keeping the body here would only guarantee
 * that the next fix lands in one of them. All state stays in the painter and in locals on purpose: a
 * mixin that declares extra fields or helper methods risks colliding with members of the target
 * class.
 */
@Mixin(Hud.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        HudIndicatorPainter.paint(graphics);
    }
}
