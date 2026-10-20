package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.hud.BossBarHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

@Mixin(BossBarHud.class)
abstract class BossBarHudContextMixin {
    @Inject(method = "draw()V", at = @At("HEAD"))
    private void universalTranslator$enterBossBar(CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.BOSS_BAR);
    }

    @Inject(method = "draw()V", at = @At("RETURN"))
    private void universalTranslator$leaveBossBar(CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
