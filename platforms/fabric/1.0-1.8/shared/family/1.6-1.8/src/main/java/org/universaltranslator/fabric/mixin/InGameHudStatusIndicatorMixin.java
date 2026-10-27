package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.GameGui;
import net.minecraft.client.render.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.6.1-1.7.10 render entry points.
 *
 * <p>This layer is only compiled when a version applies platforms/fabric/1.0-1.8/shared.gradle, and
 * that is 1.3.1-1.7.10: the nine 1.8-1.8.8 versions under this tree apply 1.8-1.12/shared.gradle
 * instead. The renderHotbar injection below therefore never resolves for them - it stays as a
 * defensive hook for 1.7.x builds whose GameGui exposes only that entry point, and require = 0
 * keeps the unused one silent instead of failing the build.
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
