package org.universaltranslator.fabric.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;

/**
 * Adapts outgoing translation to the 1.19.2 chat entry point.
 *
 * <p>1.19.2 still routes a sent message through {@code ClientPlayerEntity.sendChatMessage(String,
 * Text)}, so this is the layer that sees the text <em>and</em> the local preview the client would
 * echo. The 1.19.3/1.19.4 copy sits one layer lower (on {@code ClientPlayNetworkHandler}) because
 * that overload disappeared; the two are not interchangeable, and the preview argument here is the
 * only place the echo can be adjusted.
 *
 * <p>{@code interceptOutgoingMessage} answers "the mod already handled this message", hence the
 * negated test: a {@code true} answer must not cancel the vanilla send.
 */
@Mixin(ClientPlayerEntity.class)
abstract class ClientPlayerEntityMixin {
    @Inject(
            method = "sendChatMessage(Ljava/lang/String;Lnet/minecraft/text/Text;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void universalTranslator$interceptOutgoing(
            String message, Text preview, CallbackInfo callback) {
        if (!UniversalTranslatorFabricClient.interceptOutgoingMessage(message)) {
            callback.cancel();
        }
    }
}
