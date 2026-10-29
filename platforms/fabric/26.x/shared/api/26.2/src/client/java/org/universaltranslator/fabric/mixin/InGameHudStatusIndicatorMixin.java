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
 * <p>Targets {@code Hud} from 26.2 and {@code Gui} before it; the class kept the {@code InGameHud*}
 * file name from the 1.21 tree, so a search for {@code Hud.class} finds no mixin here. The
 * {@code @Mixin} target below is the authority, not the file name.
 *
 * <p>Everything it draws comes from {@code HudIndicatorSettings}: the corner, size, margin, colour,
 * what appears inside the square, and when it is drawn at all. Only the optional label needs a lang
 * entry; the rest is rectangles and literal text.
 *
 * <p>The painting itself lives in {@code HudIndicatorPainter} (shared/common), because this class
 * is instantiated once per 26.x API layer with nothing but a different {@code @Mixin} target: 26.2
 * and 26.3 rename the HUD class {@code Hud}, and their copies used to differ by a javadoc line. All
 * state stays in the painter and in locals on purpose: a mixin that declares extra fields or helper
 * methods risks colliding with members of the target class.
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
