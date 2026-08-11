package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

@Mixin(BossHealthOverlay.class)
abstract class BossBarHudContextMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("HEAD"))
    private void universalTranslator$enterBossBar(
            GuiGraphicsExtractor graphics, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.BOSS_BAR);
    }

    @Inject(
            method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At("RETURN"))
    private void universalTranslator$leaveBossBar(
            GuiGraphicsExtractor graphics, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
