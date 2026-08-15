package org.universaltranslator.fabric.mixin;

import net.minecraft.client.entity.mob.player.LocalClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;

@Mixin(LocalClientPlayerEntity.class)
abstract class LocalClientPlayerEntityMixin {
    @Inject(method = "sendChat(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void universalTranslator$interceptOutgoing(String message, CallbackInfo callback) {
        if (!UniversalTranslatorFabricClient.interceptOutgoingMessage(message)) {
            callback.cancel();
        }
    }
}
