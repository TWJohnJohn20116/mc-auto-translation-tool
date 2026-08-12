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
import org.universaltranslator.forge.TranslationRenderContext;

@Mixin(value = PlayerTabOverlay.class, remap = false)
abstract class PlayerListHudContextMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void universalTranslator$enterPlayerList(
            GuiGraphics context,
            int scaledWindowWidth,
            Scoreboard scoreboard,
            Objective objective,
            CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.PLAYER_LIST_HEADER);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void universalTranslator$leavePlayerList(
            GuiGraphics context,
            int scaledWindowWidth,
            Scoreboard scoreboard,
            Objective objective,
            CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
