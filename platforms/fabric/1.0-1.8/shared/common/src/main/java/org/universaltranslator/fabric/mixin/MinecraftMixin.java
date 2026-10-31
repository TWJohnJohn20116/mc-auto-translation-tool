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
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Unique
    private boolean universalTranslator$uHeld;
    @Unique
    private boolean universalTranslator$f8Held;
    @Unique
    private boolean universalTranslator$hotkeyReported;

    // require = 1: this is the only entry point for the hotkeys and for the client task queue on
    // 1.3.1-1.7.10. Losing it silently (which is what require = 0 did) leaves the keys doing
    // nothing with no line in the log; Mixin now fails the class loudly instead.
    @Inject(method = "tick()V", at = @At("RETURN"), require = 1)
    private void universalTranslator$pollKeys(CallbackInfo callback) {
        // Runs FabricClientBootstrapBase.onClientTick(), which starts by restoring the render context
        // (a render method that threw between its HEAD and RETURN injection left a text-input depth
        // above zero, which silently disables translation for the session) and then drives the join
        // hint and the runtime status line. The hook used to be an empty method, so none of it ran.
        UniversalTranslatorFabricClient.tickFromMixin();
        // The hotkeys come from universal-translator.properties (settings-key / toggle-key): this
// generation has no key-binding API, so they cannot live in the vanilla controls screen.
int settingsCode = HotkeyKeys.settingsLwjgl2Code();
int toggleCode = HotkeyKeys.toggleLwjgl2Code();
universalTranslator$reportUnmappableHotkey(settingsCode, toggleCode);
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
    }

    /**
     * Records once that a configured key name has no LWJGL2 code, which is why pressing it does
     * nothing in these generations while the same name works on 1.13+.
     */
    @Unique
    private void universalTranslator$reportUnmappableHotkey(int settingsCode, int toggleCode) {
        if (universalTranslator$hotkeyReported || (settingsCode >= 0 && toggleCode >= 0)) {
            return;
        }
        universalTranslator$hotkeyReported = true;
        OrnitheClientAccess.reportHotkey(settingsCode < 0, toggleCode < 0);
    }
}
