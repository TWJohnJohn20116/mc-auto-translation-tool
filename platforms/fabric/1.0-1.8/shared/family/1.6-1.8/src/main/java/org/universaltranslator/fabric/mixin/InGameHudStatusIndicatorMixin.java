package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.GameGui;
import net.minecraft.client.render.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.6.1-1.8.1 render entry points.
 *
 * <p>Both injections carry require = 0: 1.6.1-1.7.10 render through render(FZII)V, which already
 * carries the GUI-scaled size, while 1.8 switched to render(F)V and only renderHotbar still
 * receives the Window. Exactly one resolves per version and the other must stay silent instead of
 * failing the build.
 */
@Mixin(GameGui.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(method = "render(FZII)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$renderStatusIndicatorLegacy(float delta, boolean flag, int width, int height, CallbackInfo callback) {
        HudIndicatorOverlay.render(width, height);
    }

    @Inject(method = "renderHotbar(Lnet/minecraft/client/render/Window;F)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$renderStatusIndicator(Window window, float delta, CallbackInfo callback) {
        if (window == null) {
            return;
        }
        HudIndicatorOverlay.render((int) window.getScaledWidth(), (int) window.getScaledHeight());
    }
}
