package org.universaltranslator.forge.mixin;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.EditSignScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.forge.TranslationRenderContext;
@Mixin(EditSignScreen.class)
abstract class SignEditScreenMixin {
 @Inject(method="render(Lcom/mojang/blaze3d/matrix/MatrixStack;IIF)V",at=@At("HEAD")) private void enter(MatrixStack m,int x,int y,float d,CallbackInfo c){TranslationRenderContext.pushTextInput();}
 @Inject(method="render(Lcom/mojang/blaze3d/matrix/MatrixStack;IIF)V",at=@At("RETURN")) private void leave(MatrixStack m,int x,int y,float d,CallbackInfo c){TranslationRenderContext.popTextInput();}
}