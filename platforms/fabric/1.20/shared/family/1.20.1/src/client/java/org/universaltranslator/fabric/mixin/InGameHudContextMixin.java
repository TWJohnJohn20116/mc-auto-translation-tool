package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

/**
 * Tags the HUD regions with the {@link TextKind} they draw.
 *
 * <p>Carries the same five regions the 1.21 copy tags (chat, scoreboard, player list, title and
 * action bar). Tagging only the scoreboard left the per-kind switches — {@code translate-title},
 * {@code translate-action-bar} — with nothing to switch, and filed every other HUD line under the
 * generic kind in the statistics.
 *
 * <p>The four region methods only exist from 1.20.5, and there they take a plain {@code float}: the
 * {@code RenderTickCounter} parameter did not arrive until 1.21. 1.20 through 1.20.4 has no
 * per-region HUD method at all (only {@code render(DrawContext,float)} and the scoreboard one), so
 * their pairs are {@code require = 0}: a version without the method loses that region's tag, never
 * the launch.
 */
@Mixin(InGameHud.class)
abstract class InGameHudContextMixin {
    @Inject(
            method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"))
    private void universalTranslator$enterScoreboard(
            DrawContext context, ScoreboardObjective objective, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.SCOREBOARD_LINE);
    }

    @Inject(
            method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("RETURN"))
    private void universalTranslator$leaveScoreboard(
            DrawContext context, ScoreboardObjective objective, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }

    @Inject(
            method = "renderChat(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$enterChat(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(
            method = "renderChat(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveChat(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }

    @Inject(
            method = "renderPlayerList(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$enterPlayerList(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.PLAYER_LIST_HEADER);
    }

    @Inject(
            method = "renderPlayerList(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$leavePlayerList(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }

    @Inject(
            method = "renderTitleAndSubtitle(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$enterTitle(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.TITLE);
    }

    @Inject(
            method = "renderTitleAndSubtitle(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveTitle(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }

    @Inject(
            method = "renderOverlayMessage(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$enterActionBar(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.ACTION_BAR);
    }

    @Inject(
            method = "renderOverlayMessage(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveActionBar(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
