package org.universaltranslator.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.forge.TranslationRenderContext;

@Mixin(ChatComponent.class)
abstract class ChatHudContextMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void universalTranslator$enterChat(
            PoseStack context, int currentTick, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void universalTranslator$leaveChat(
            PoseStack context, int currentTick, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
