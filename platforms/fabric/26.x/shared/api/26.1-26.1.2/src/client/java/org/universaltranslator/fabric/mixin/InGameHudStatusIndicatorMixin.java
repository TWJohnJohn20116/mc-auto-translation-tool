package org.universaltranslator.fabric.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * <p>The painting itself lives in {@code HudIndicatorPainter} (shared/common), because this class
 * is instantiated once per 26.x API layer with nothing but a different {@code @Mixin} target: 26.1
 * through 26.1.2 still name the HUD class {@code Gui}, 26.2 and 26.3 name it {@code Hud}. All state
 * stays in the painter and in locals on purpose: a mixin that declares extra fields or helper
 * methods risks colliding with members of the target class.
 */
@Mixin(Gui.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        // 26.1 through 26.1.2 no longer expose the HUD-hidden flag on Options, Gui or Minecraft (all
        // three reference the name but declare no such member), and the HUD render is skipped by Gui
        // while F1 hides it, so there is nothing to read here.
        HudIndicatorPainter.paint(graphics, false);
    }
}
