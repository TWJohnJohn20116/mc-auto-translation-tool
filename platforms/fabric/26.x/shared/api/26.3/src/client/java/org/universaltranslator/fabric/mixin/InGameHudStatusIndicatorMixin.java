package org.universaltranslator.fabric.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * <p>The painting itself lives in {@code HudIndicatorPainter} (shared/common): the 26.3 copy used to
 * be byte-identical to 26.2 apart from its javadoc, so keeping the body here would only guarantee
 * that the next fix lands in one of them. All state stays in the painter and in locals on purpose:
 * the one member declared here is the {@code @Shadow} of {@code Hud.isHidden}, which is the
 * sanctioned way to read a field of the target class — 26.3 dropped {@code Options.hideGui}, so the
 * HUD-hidden flag lives on {@code Hud}.
 */
@Mixin(Hud.class)
abstract class InGameHudStatusIndicatorMixin {
    @Shadow
    private boolean isHidden;

    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        // 26.3 renders the HUD through Hud, and F1 sets Hud.isHidden (private, hence the @Shadow
        // above) instead of the Options.hideGui the 26.1.x copy reads. The render still runs while
        // the HUD is hidden, so the flag has to reach the painter or the square, its label and the
        // outgoing preview stay on an otherwise empty screen.
        HudIndicatorPainter.paint(graphics, this.isHidden);
    }
}
