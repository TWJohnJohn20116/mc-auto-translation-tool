package org.universaltranslator.fabric.mixin;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

/**
 * Marks chat lines as chat while {@code ChatHud.render} runs.
 *
 * <p>1.19.3+ does not list this class: the family config for 1.19.3-1.19.4 uses
 * {@code ChatHudRenderContextMixin} instead, because that render method takes a {@code DrawContext}
 * there. This copy is mounted for 1.16-1.19.2 only.
 */
@Mixin(ChatHud.class)
abstract class ChatHudContextMixin {
    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;I)V", at = @At("HEAD"))
    private void universalTranslator$enterChat(
            MatrixStack matrices, int currentTick, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "render(Lnet/minecraft/client/util/math/MatrixStack;I)V", at = @At("RETURN"))
    private void universalTranslator$leaveChat(
            MatrixStack matrices, int currentTick, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
