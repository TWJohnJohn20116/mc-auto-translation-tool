package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.universaltranslator.forge.RenderedTextBridge;

import java.util.List;

/** Translates the canonical item tooltip list before inventory screens render it. */
@Mixin(Screen.class)
abstract class ScreenMixin {
    @Inject(
            method = "getTooltipFromItem(Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true,
            require = 0)
    private void universalTranslator$translateItemTooltip(
            ItemStack stack,
            CallbackInfoReturnable<List<Component>> callback) {
        callback.setReturnValue(RenderedTextBridge.translateItemTooltip(callback.getReturnValue()));
    }
}
