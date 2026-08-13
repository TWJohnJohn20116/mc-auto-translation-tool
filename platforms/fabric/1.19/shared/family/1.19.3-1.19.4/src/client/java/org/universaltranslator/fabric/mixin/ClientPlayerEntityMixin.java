package org.universaltranslator.fabric.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;

/** Adapts outgoing translation to the pre-1.19.3 chat entry point. */
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
