package org.universaltranslator.forge.mixin;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.IngameGui;
import net.minecraft.scoreboard.ScoreObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.TranslationRenderContext;
@Mixin(IngameGui.class)
abstract class InGameHudContextMixin {
 @Inject(method="displayScoreboardSidebar(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/scoreboard/ScoreObjective;)V",at=@At("HEAD"),require=0) private void enter(MatrixStack m,ScoreObjective o,CallbackInfo c){TranslationRenderContext.push(TextKind.SCOREBOARD_LINE);}
 @Inject(method="displayScoreboardSidebar(Lcom/mojang/blaze3d/matrix/MatrixStack;Lnet/minecraft/scoreboard/ScoreObjective;)V",at=@At("RETURN"),require=0) private void leave(MatrixStack m,ScoreObjective o,CallbackInfo c){TranslationRenderContext.pop();}
}
