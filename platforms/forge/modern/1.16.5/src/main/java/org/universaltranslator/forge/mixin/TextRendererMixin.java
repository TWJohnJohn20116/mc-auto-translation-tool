package org.universaltranslator.forge.mixin;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.IReorderingProcessor;
import net.minecraft.util.text.ITextProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.forge.RenderedTextBridge;
@Mixin(FontRenderer.class)
abstract class TextRendererMixin {
 @ModifyVariable(method="drawInternal(Ljava/lang/String;FFIZLnet/minecraft/util/math/vector/Matrix4f;Lnet/minecraft/client/renderer/IRenderTypeBuffer;ZIIZ)I",at=@At("HEAD"),argsOnly=true) private String drawString(String t){return RenderedTextBridge.translate(t);}
 @ModifyVariable(method="drawInternal(Lnet/minecraft/util/IReorderingProcessor;FFIZLnet/minecraft/util/math/vector/Matrix4f;Lnet/minecraft/client/renderer/IRenderTypeBuffer;ZII)I",at=@At("HEAD"),argsOnly=true) private IReorderingProcessor drawOrdered(IReorderingProcessor t){return RenderedTextBridge.translate(t);}
 @ModifyVariable(method="width(Ljava/lang/String;)I",at=@At("HEAD"),argsOnly=true) private String widthString(String t){return RenderedTextBridge.translate(t);}
 @ModifyVariable(method="width(Lnet/minecraft/util/text/ITextProperties;)I",at=@At("HEAD"),argsOnly=true) private ITextProperties widthText(ITextProperties t){return RenderedTextBridge.translate(t);}
 @ModifyVariable(method="width(Lnet/minecraft/util/IReorderingProcessor;)I",at=@At("HEAD"),argsOnly=true) private IReorderingProcessor widthOrdered(IReorderingProcessor t){return RenderedTextBridge.translate(t);}
}