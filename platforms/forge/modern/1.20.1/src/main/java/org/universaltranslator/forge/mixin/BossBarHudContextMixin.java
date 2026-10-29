package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.ForgeMixinDiagnostics;
import org.universaltranslator.forge.TranslationRenderContext;

@Mixin(BossHealthOverlay.class)
abstract class BossBarHudContextMixin {
    // Optional: a boss bar translated with the neutral kind is still translated, it only loses the
    // kind-specific prompt. require = 0 keeps a Forge signature change from blocking the launch;
    // the first run is reported so the debug log shows the hook is alive.
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void universalTranslator$enterBossBar(GuiGraphics context, CallbackInfo callback) {
        ForgeMixinDiagnostics.optionalHookActive(ForgeMixinDiagnostics.BOSS_BAR_CONTEXT, "boss-bar");
        TranslationRenderContext.push(TextKind.BOSS_BAR);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveBossBar(GuiGraphics context, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
