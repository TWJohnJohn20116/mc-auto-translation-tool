package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.FabricTranslationRuntime;

/**
 * Paints a small always-on translation status indicator in the top-left corner of the HUD so the
 * player can tell at a glance whether translation is currently on.
 *
 * <p>Only coloured rectangles are drawn, so the indicator needs neither a texture nor a lang entry.
 * All state lives in locals on purpose: a mixin that declares extra fields or helper methods risks
 * colliding with members of the target class.
 */
@Mixin(InGameHud.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(
            method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("RETURN"))
    private void universalTranslator$renderStatusIndicator(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        int left = 4;
        int top = 4;
        int right = left + 6;
        int bottom = top + 6;
        int borderColor = 0xFF000000;
        // One pixel of opaque black keeps the indicator readable against sky and bright terrain.
        context.fill(left - 1, top - 1, right + 1, top, borderColor);
        context.fill(left - 1, bottom, right + 1, bottom + 1, borderColor);
        context.fill(left - 1, top, left, bottom, borderColor);
        context.fill(right, top, right + 1, bottom, borderColor);
        if (FabricTranslationRuntime.homeSettings().isEnabled()) {
            // Solid green while translation is on.
            context.fill(left, top, right, bottom, 0xFF55FF55);
            return;
        }
        // A hollow red frame while translation is off, so the two states differ in shape as well as
        // in colour and remain distinguishable for players who cannot separate the two hues.
        int disabledColor = 0xFFFF5555;
        context.fill(left, top, right, top + 1, disabledColor);
        context.fill(left, bottom - 1, right, bottom, disabledColor);
        context.fill(left, top + 1, left + 1, bottom - 1, disabledColor);
        context.fill(right - 1, top + 1, right, bottom - 1, disabledColor);
    }
}
