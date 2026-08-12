package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.TranslationRenderContext;

@Mixin(value = BossHealthOverlay.class, remap = false)
abstract class BossBarHudContextMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void universalTranslator$enterBossBar(GuiGraphics context, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.BOSS_BAR);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void universalTranslator$leaveBossBar(GuiGraphics context, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
