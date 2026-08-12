package org.universaltranslator.forge.mixin;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.universaltranslator.forge.RenderedTextBridge;
import java.util.List;
@Mixin(Screen.class)
abstract class ScreenMixin {
 @Inject(method="getTooltipFromItem(Lnet/minecraft/item/ItemStack;)Ljava/util/List;",at=@At("RETURN"),cancellable=true,require=0)
 private void translate(ItemStack stack,CallbackInfoReturnable<List<ITextComponent>> c){c.setReturnValue(RenderedTextBridge.translateItemTooltip(c.getReturnValue()));}
}
