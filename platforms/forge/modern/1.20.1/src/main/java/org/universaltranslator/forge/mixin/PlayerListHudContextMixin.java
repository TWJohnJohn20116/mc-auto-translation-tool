package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.ForgeMixinDiagnostics;
import org.universaltranslator.forge.TranslationRenderContext;

@Mixin(PlayerTabOverlay.class)
abstract class PlayerListHudContextMixin {
    // Optional: the tab list keeps translating with the neutral kind when the hook is missing.
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void universalTranslator$enterPlayerList(
            GuiGraphics context,
            int scaledWindowWidth,
            Scoreboard scoreboard,
            Objective objective,
            CallbackInfo callback) {
        ForgeMixinDiagnostics.optionalHookActive(
                ForgeMixinDiagnostics.PLAYER_LIST_CONTEXT, "player-list");
        TranslationRenderContext.push(TextKind.PLAYER_LIST_HEADER);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void universalTranslator$leavePlayerList(
            GuiGraphics context,
            int scaledWindowWidth,
            Scoreboard scoreboard,
            Objective objective,
            CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
