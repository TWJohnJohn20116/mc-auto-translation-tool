package org.universaltranslator.fabric.mixin;

import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;

@Mixin(LocalClientPlayerEntity.class)
abstract class LocalClientPlayerEntityMixin {
    // require = 1: outgoing chat interception is a single point of entry, so a mapping change must
    // fail loudly at class load instead of silently letting every outgoing line through.
    @Inject(method = "sendChat(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void universalTranslator$interceptOutgoing(String message, CallbackInfo callback) {
        if (!UniversalTranslatorFabricClient.interceptOutgoingMessage(message)) {
            callback.cancel();
        }
    }
}
