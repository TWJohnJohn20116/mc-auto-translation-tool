package org.universaltranslator.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.TranslationRenderContext;

@Mixin(Gui.class)
abstract class InGameHudContextMixin {
    @Inject(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V",
            at = @At("HEAD"))
    private void universalTranslator$enterScoreboard(
            PoseStack context, Objective objective, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.SCOREBOARD_LINE);
    }

    @Inject(
            method = "displayScoreboardSidebar(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V",
            at = @At("RETURN"))
    private void universalTranslator$leaveScoreboard(
            PoseStack context, Objective objective, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
