package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.TranslationRenderContext;

/**
 * Tags the chat HUD region on 1.19.3 and 1.19.4.
 *
 * <p>{@code ChatHud#render} grew two window-origin parameters in 1.19.3, so the 1.16–1.19.2 shape in
 * {@code platforms/fabric/shared/mixin-1.16-1.19} has no target here and this version lists its own
 * pair instead. Both hooks are {@code require = 0}: a descriptor that misses must cost the chat tag
 * rather than the launch, which is what the shared copy's {@code defaultRequire: 1} turned it into.
 */
@Mixin(ChatHud.class)
abstract class ChatHudRenderContextMixin {
    @Inject(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;III)V",
            at = @At("HEAD"), require = 0)
    private void universalTranslator$enterChat(
            MatrixStack matrices, int tickDelta, int mouseX, int mouseY, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(
            method = "render(Lnet/minecraft/client/util/math/MatrixStack;III)V",
            at = @At("RETURN"), require = 0)
    private void universalTranslator$leaveChat(
            MatrixStack matrices, int tickDelta, int mouseX, int mouseY, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
