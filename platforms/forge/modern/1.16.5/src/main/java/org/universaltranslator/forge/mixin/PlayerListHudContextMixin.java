package org.universaltranslator.forge.mixin;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.overlay.PlayerTabOverlayGui;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreObjective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.TranslationRenderContext;
@Mixin(PlayerTabOverlayGui.class)
abstract class PlayerListHudContextMixin {
 @Inject(method="render(Lcom/mojang/blaze3d/matrix/MatrixStack;ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V",at=@At("HEAD")) private void enter(MatrixStack m,int w,Scoreboard s,ScoreObjective o,CallbackInfo c){TranslationRenderContext.push(TextKind.PLAYER_LIST_HEADER);}
 @Inject(method="render(Lcom/mojang/blaze3d/matrix/MatrixStack;ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V",at=@At("RETURN")) private void leave(MatrixStack m,int w,Scoreboard s,ScoreObjective o,CallbackInfo c){TranslationRenderContext.pop();}
}