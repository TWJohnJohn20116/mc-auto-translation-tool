package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

@Mixin(InGameHud.class)
abstract class InGameHudContextMixin {
    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/scoreboard/ScoreboardObjective;)V", at = @At("HEAD"))
    private void universalTranslator$enterScoreboard(ScoreboardObjective objective, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.SCOREBOARD_LINE);
    }

    @Inject(method = "renderScoreboardSidebar(Lnet/minecraft/scoreboard/ScoreboardObjective;)V", at = @At("RETURN"))
    private void universalTranslator$leaveScoreboard(ScoreboardObjective objective, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
