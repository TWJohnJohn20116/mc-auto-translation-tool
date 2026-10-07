package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.GameGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 1.3.1-1.5.2 render entry point. */
@Mixin(GameGui.class)
abstract class InGameHudStatusIndicatorMixin {
    @Inject(method = "render(FZII)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$renderStatusIndicator(float delta, boolean flag, int width, int height, CallbackInfo callback) {
        HudIndicatorOverlay.render(width, height);
    }
}