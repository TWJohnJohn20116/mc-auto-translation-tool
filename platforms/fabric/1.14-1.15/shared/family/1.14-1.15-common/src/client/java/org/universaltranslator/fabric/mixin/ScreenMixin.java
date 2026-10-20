package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.universaltranslator.fabric.RenderedTextBridge;

import java.util.List;

/** Translates the canonical item tooltip list before inventory screens render it. */
@Mixin(Screen.class)
abstract class ScreenMixin {
    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true)
    private void universalTranslator$translateItemTooltip(
            ItemStack stack,
            CallbackInfoReturnable<List<Text>> callback) {
        callback.setReturnValue(RenderedTextBridge.translateItemTooltip(callback.getReturnValue()));
    }
}
