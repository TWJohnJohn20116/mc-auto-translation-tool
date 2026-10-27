package org.universaltranslator.fabric.mixin;

import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HotkeyKeys;
import org.universaltranslator.fabric.OrnitheClientAccess;
import org.universaltranslator.fabric.TranslationRenderContext;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Unique
    private boolean universalTranslator$uHeld;
    @Unique
    private boolean universalTranslator$f8Held;

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void universalTranslator$pollKeys(CallbackInfo callback) {
        // Drop any render context left latched by a render method that threw before its RETURN
        // injection could pop it (Mixin 0.8.x has no exception exit injection point).
        TranslationRenderContext.reset();
        // The hotkeys come from universal-translator.properties (settings-key / toggle-key): this
// generation has no key-binding API, so they cannot live in the vanilla controls screen.
int settingsCode = HotkeyKeys.settingsLwjgl2Code();
int toggleCode = HotkeyKeys.toggleLwjgl2Code();
boolean u = settingsCode > 0 && Keyboard.isKeyDown(settingsCode);
        boolean f8 = toggleCode > 0 && Keyboard.isKeyDown(toggleCode);
        if (u && !universalTranslator$uHeld) {
            UniversalTranslatorFabricClient.openSettingsFromKey();
        }
        if (f8 && !universalTranslator$f8Held) {
            UniversalTranslatorFabricClient.toggleFromKey();
        }
        universalTranslator$uHeld = u;
        universalTranslator$f8Held = f8;
        OrnitheClientAccess.runClientTasks();
        Minecraft client = (Minecraft) (Object) this;
        UniversalTranslatorFabricClient.notifyRuntimeStatus(client.getNetworkHandler() != null);
    }
}
