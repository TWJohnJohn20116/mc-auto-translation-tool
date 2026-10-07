package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.6.1-1.8.1 render entry points.
 *
 * <p>Both injections carry require = 0: the signature changed at 1.8, so exactly one of them
 * resolves per version and the other must stay silent instead of failing the build.
 */
@Mixin(GameGui.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(method = "render(FZII)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$renderStatusIndicatorLegacy(float delta, boolean flag, int width, int height, CallbackInfo callback) {
        HudIndicatorOverlay.render();
    }

    @Inject(method = "render(F)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$renderStatusIndicator(float delta, CallbackInfo callback) {
        HudIndicatorOverlay.render();
    }
}