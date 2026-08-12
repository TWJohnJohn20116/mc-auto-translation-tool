package org.universaltranslator.forge.mixin;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.NewChatGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.TranslationRenderContext;
@Mixin(NewChatGui.class)
abstract class ChatHudContextMixin {
 @Inject(method="render(Lcom/mojang/blaze3d/matrix/MatrixStack;I)V",at=@At("HEAD")) private void enter(MatrixStack m,int tick,CallbackInfo c){TranslationRenderContext.push(TextKind.CHAT);}
 @Inject(method="render(Lcom/mojang/blaze3d/matrix/MatrixStack;I)V",at=@At("RETURN")) private void leave(MatrixStack m,int tick,CallbackInfo c){TranslationRenderContext.pop();}
}