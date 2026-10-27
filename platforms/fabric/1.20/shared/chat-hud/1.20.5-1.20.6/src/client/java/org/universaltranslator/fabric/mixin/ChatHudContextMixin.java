package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.ChatImageCompatibility;
import org.universaltranslator.fabric.TranslationRenderContext;

// ChatImage uses the default priority (1000). Applying this mixin afterwards places its composable
// HEAD ModifyVariable before ChatImage at runtime.
//
// This file was copied from the 1.20-1.20.4 layer when 1.20.5 added the `boolean focused` parameter to
// render(), and the copy kept only the two render injections: the addMessage normalisation - and with
// it the priority adjustment it needs - went missing for 1.20.5/1.20.6, even though
// ChatImageCompatibility is still compiled into those jars. Keep the two layers in sync.
@Mixin(value = ChatHud.class, priority = 800)
abstract class ChatHudContextMixin {
    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"),
            argsOnly = true)
    private Text universalTranslator$normalizeChatImageArguments(Text message) {
        return ChatImageCompatibility.normalizeArguments(message);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIIZ)V", at = @At("HEAD"))
    private void universalTranslator$enterChat(
            DrawContext context, int currentTick, int mouseX, int mouseY, boolean focused,
            CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.CHAT);
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIIZ)V", at = @At("RETURN"))
    private void universalTranslator$leaveChat(
            DrawContext context, int currentTick, int mouseX, int mouseY, boolean focused,
            CallbackInfo callback) {
        TranslationRenderContext.pop();
    }
}
