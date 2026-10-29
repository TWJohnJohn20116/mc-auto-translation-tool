package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

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
    @Inject(method = "renderChat", at = @At("HEAD"), require = 0)
    private void universalTranslator$enterChat(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "renderChat", at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveChat(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
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
        TranslationRenderContext.push(TextKind.PLAYER_LIST_HEADER);
    }

    @Inject(method = "renderPlayerList", at = @At("RETURN"), require = 0)
    private void universalTranslator$leavePlayerList(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }

    @Inject(method = "renderTitleAndSubtitle", at = @At("HEAD"), require = 0)
    private void universalTranslator$enterTitle(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.TITLE);
    }

    @Inject(method = "renderTitleAndSubtitle", at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveTitle(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"), require = 0)
    private void universalTranslator$enterActionBar(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.ACTION_BAR);
    }

    @Inject(method = "renderOverlayMessage", at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveActionBar(
            DrawContext context, RenderTickCounter tickCounter, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
