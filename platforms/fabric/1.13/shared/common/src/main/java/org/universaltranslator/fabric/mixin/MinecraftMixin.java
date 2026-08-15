package org.universaltranslator.fabric.mixin;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Unique
    private boolean universalTranslator$uHeld;
    @Unique
    private boolean universalTranslator$f8Held;

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void universalTranslator$pollKeys(CallbackInfo callback) {
        Minecraft client = (Minecraft) (Object) this;
        long window = client.window.getWindow();
        boolean u = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_U) == GLFW.GLFW_PRESS;
        boolean f8 = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
        if (u && !universalTranslator$uHeld) {
            UniversalTranslatorFabricClient.openSettingsFromKey();
        }
        if (f8 && !universalTranslator$f8Held) {
            UniversalTranslatorFabricClient.toggleFromKey();
        }
        universalTranslator$uHeld = u;
        universalTranslator$f8Held = f8;
        UniversalTranslatorFabricClient.notifyRuntimeStatus(client.getNetworkHandler() != null);
    }
}
