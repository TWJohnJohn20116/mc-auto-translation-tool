package org.universaltranslator.fabric.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;

/**
 * Adapts outgoing translation to the 1.19.3/1.19.4 chat entry point.
 *
 * <p>1.19.3 dropped {@code ClientPlayerEntity.sendChatMessage(String, Text)}, so this hook sits one
 * layer lower on {@code ClientPlayNetworkHandler.sendChatMessage(String)}. The consequence is that
 * the local echo has already been produced by the time this runs: unlike the 1.19.2 copy, the
 * preview cannot be adjusted here.
 *
 * <p>{@code interceptOutgoingMessage} answers "the mod already handled this message", hence the
 * negated test: a {@code true} answer must not cancel the vanilla send.
 */
@Mixin(ClientPlayNetworkHandler.class)
abstract class ClientPlayerEntityMixin {
    @Inject(
            method = "sendChatMessage(Ljava/lang/String;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void universalTranslator$interceptOutgoing(
            String message, CallbackInfo callback) {
        if (!UniversalTranslatorFabricClient.interceptOutgoingMessage(message)) {
            callback.cancel();
        }
    }
}
