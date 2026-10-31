package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.scoreboard.ScoreboardObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.RenderedTextBridge;
import org.universaltranslator.fabric.TranslationRenderContext;

import java.util.concurrent.atomic.AtomicBoolean;

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
    // One flag per require = 0 hook: only a hook that actually fired can separate a drifted
    // descriptor from a version that never had that entry point.
    private static final AtomicBoolean universalTranslator$enterChatSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$leaveChatSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$enterPlayerListSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$leavePlayerListSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$enterTitleSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$leaveTitleSurface = new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$enterActionBarSurface =
            new AtomicBoolean();
    private static final AtomicBoolean universalTranslator$leaveActionBarSurface =
            new AtomicBoolean();

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
        RenderedTextBridge.surface("hud-chat-enter-1.20", universalTranslator$enterChatSurface);
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(
            method = "renderChat(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveChat(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-chat-leave-1.20", universalTranslator$leaveChatSurface);
        TranslationRenderContext.pop();
    }

    @Inject(
            method = "renderPlayerList(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$enterPlayerList(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-player-list-enter-1.20",
                universalTranslator$enterPlayerListSurface);
        TranslationRenderContext.push(TextKind.PLAYER_LIST_HEADER);
    }

    @Inject(
            method = "renderPlayerList(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$leavePlayerList(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-player-list-leave-1.20",
                universalTranslator$leavePlayerListSurface);
        TranslationRenderContext.pop();
    }

    @Inject(
            method = "renderTitleAndSubtitle(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$enterTitle(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-title-enter-1.20", universalTranslator$enterTitleSurface);
        TranslationRenderContext.push(TextKind.TITLE);
    }

    @Inject(
            method = "renderTitleAndSubtitle(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveTitle(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-title-leave-1.20", universalTranslator$leaveTitleSurface);
        TranslationRenderContext.pop();
    }

    @Inject(
            method = "renderOverlayMessage(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$enterActionBar(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-action-bar-enter-1.20",
                universalTranslator$enterActionBarSurface);
        TranslationRenderContext.push(TextKind.ACTION_BAR);
    }

    @Inject(
            method = "renderOverlayMessage(Lnet/minecraft/client/gui/DrawContext;F)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveActionBar(
            DrawContext context, float tickDelta, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-action-bar-leave-1.20",
                universalTranslator$leaveActionBarSurface);
        TranslationRenderContext.pop();
    }
}
