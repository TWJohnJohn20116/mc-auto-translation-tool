package org.universaltranslator.fabric.mixin;

import org.universaltranslator.core.HotkeyKeys;

import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void universalTranslator$pollKeys(CallbackInfo callback) {
        // Runs FabricClientBootstrapBase.onClientTick(): the render-context reset, the join hint and
        // the runtime status line. The hook used to be dropped by registerTickHook in this tree, so
        // none of that ran; the status line is part of that tick body and is not called again below.
        UniversalTranslatorFabricClient.tickFromMixin();
        // require = 0 keeps a renamed tick()V from failing the launch, so the one thing that
        // distinguishes "the hook never applied" from "the keys stopped working" is this line.
        OrnitheClientAccess.noteTickSurface();
        try {
            universalTranslator$pollHotkeys();
        } catch (RuntimeException failure) {
            // A drifted mapping in the hotkey half used to escape through Minecraft.tick()V and take
            // the whole client down over one key press.
            OrnitheClientAccess.reportHotkeyFailure(failure);
        } catch (Error failure) {
            OrnitheClientAccess.reportHotkeyFailure(failure);
        }
    }

    /** The hotkey half of the tick injection, isolated so one failure is contained. */
    @Unique
    private void universalTranslator$pollHotkeys() {
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
