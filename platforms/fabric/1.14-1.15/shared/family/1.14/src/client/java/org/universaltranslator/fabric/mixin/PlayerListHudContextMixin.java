package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

@Mixin(PlayerListHud.class)
abstract class PlayerListHudContextMixin {
    @Inject(
            method = "draw(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"))
    private void universalTranslator$enterPlayerList(
            int scaledWindowWidth, Scoreboard scoreboard,
            ScoreboardObjective objective, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.PLAYER_LIST_HEADER);
    }

    @Inject(
            method = "draw(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("RETURN"))
    private void universalTranslator$leavePlayerList(
            int scaledWindowWidth, Scoreboard scoreboard,
            ScoreboardObjective objective, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
