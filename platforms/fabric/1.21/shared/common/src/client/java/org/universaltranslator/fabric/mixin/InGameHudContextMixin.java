package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
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
 * <p>Every hook is optional ({@code require = 0}): the point of this mixin is to add a hint about
 * which region a line came from, and when a method is renamed the correct outcome is "that region
 * falls back to the generic kind", not a client that refuses to start. The two scoreboard hooks keep
 * their descriptor because they are the ones whose signature this tree can pin.
 */
@Mixin(InGameHud.class)
abstract class InGameHudContextMixin {
    // One flag per require = 0 hook: only a hook that actually fired can separate a renamed method
    // from a version that never had that entry point.
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

    @Inject(method = "renderChat", at = @At("HEAD"), require = 0)
    private void universalTranslator$enterChat(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-chat-enter-1.21", universalTranslator$enterChatSurface);
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "renderChat", at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveChat(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-chat-leave-1.21", universalTranslator$leaveChatSurface);
        TranslationRenderContext.pop();
    }

    @Inject(
            method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"))
    private void universalTranslator$enterScoreboard(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.SCOREBOARD_LINE);
    }

    @Inject(
            method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("RETURN"))
    private void universalTranslator$leaveScoreboard(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }

    @Inject(method = "renderPlayerList", at = @At("HEAD"), require = 0)
    private void universalTranslator$enterPlayerList(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-player-list-enter-1.21",
                universalTranslator$enterPlayerListSurface);
        TranslationRenderContext.push(TextKind.PLAYER_LIST_HEADER);
    }

    @Inject(method = "renderPlayerList", at = @At("RETURN"), require = 0)
    private void universalTranslator$leavePlayerList(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-player-list-leave-1.21",
                universalTranslator$leavePlayerListSurface);
        TranslationRenderContext.pop();
    }

    @Inject(method = "renderTitleAndSubtitle", at = @At("HEAD"), require = 0)
    private void universalTranslator$enterTitle(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-title-enter-1.21", universalTranslator$enterTitleSurface);
        TranslationRenderContext.push(TextKind.TITLE);
    }

    @Inject(method = "renderTitleAndSubtitle", at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveTitle(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-title-leave-1.21", universalTranslator$leaveTitleSurface);
        TranslationRenderContext.pop();
    }

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"), require = 0)
    private void universalTranslator$enterActionBar(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-action-bar-enter-1.21",
                universalTranslator$enterActionBarSurface);
        TranslationRenderContext.push(TextKind.ACTION_BAR);
    }

    @Inject(method = "renderOverlayMessage", at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveActionBar(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        RenderedTextBridge.surface("hud-action-bar-leave-1.21",
                universalTranslator$leaveActionBarSurface);
        TranslationRenderContext.pop();
    }
}
