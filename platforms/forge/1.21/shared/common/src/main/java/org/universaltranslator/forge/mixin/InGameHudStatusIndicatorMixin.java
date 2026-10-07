package org.universaltranslator.forge.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.forge.ForgeTranslationRuntime;

/**
 * Paints a small always-on translation status indicator in the top-left corner of the HUD so the
 * player can tell at a glance whether translation is currently on.
 *
 * <p>Only coloured rectangles are drawn, so the indicator needs neither a texture nor a lang entry.
 * Forge runs on official Mojang mappings, hence {@code remap = false}. All state lives in locals on
 * purpose: a mixin that declares extra fields or helper methods risks colliding with members of the
 * target class.
 */
@Mixin(value = Gui.class, remap = false)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo callback) {
        // Read the settings first: a disabled indicator draws nothing at all.
        if (!ForgeTranslationRuntime.homeSettings().isHudIndicator()) {
            return;
        }
        HudIndicatorCorner corner = ForgeTranslationRuntime.homeSettings().getHudIndicatorCorner();
        int margin = 4;
        int size = 6;
        int left = corner.isRight() ? graphics.guiWidth() - margin - size : margin;
        int top = corner.isBottom() ? graphics.guiHeight() - margin - size : margin;
        int right = left + size;
        int bottom = top + size;
        int borderColor = 0xFF000000;
        // One pixel of opaque black keeps the indicator readable against sky and bright terrain.
        graphics.fill(left - 1, top - 1, right + 1, top, borderColor);
        graphics.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        graphics.fill(left - 1, top, left, bottom, borderColor);
        graphics.fill(right, top, right + 1, bottom, borderColor);
        if (ForgeTranslationRuntime.homeSettings().isEnabled()) {
            // Solid green while translation is on.
            graphics.fill(left, top, right, bottom, 0xFF55FF55);
            return;
        }
        // A hollow red frame while translation is off, so the two states differ in shape as well as
        // in colour and remain distinguishable for players who cannot separate the two hues.
        int disabledColor = 0xFFFF5555;
        graphics.fill(left, top, right, top + 1, disabledColor);
        graphics.fill(left, bottom - 1, right, bottom, disabledColor);
        graphics.fill(left, top + 1, left + 1, bottom - 1, disabledColor);
        graphics.fill(right - 1, top + 1, right, bottom - 1, disabledColor);
    }
}
