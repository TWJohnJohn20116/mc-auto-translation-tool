package org.universaltranslator.forge.mixin;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.overlay.BossOverlayGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.TranslationRenderContext;
@Mixin(BossOverlayGui.class)
abstract class BossBarHudContextMixin {
 @Inject(method="render",at=@At("HEAD")) private void enter(MatrixStack m, CallbackInfo c){TranslationRenderContext.push(TextKind.BOSS_BAR);}
 @Inject(method="render",at=@At("RETURN")) private void leave(MatrixStack m, CallbackInfo c){TranslationRenderContext.pop();}
}