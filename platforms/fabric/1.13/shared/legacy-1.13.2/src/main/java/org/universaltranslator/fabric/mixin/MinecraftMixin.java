package org.universaltranslator.fabric.mixin;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HotkeyKeys;
import org.universaltranslator.fabric.OrnitheClientAccess;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;

@Mixin(MinecraftClient.class)
abstract class MinecraftMixin {
    @Unique
    private boolean universalTranslator$uHeld;
    @Unique
    private boolean universalTranslator$f8Held;

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void universalTranslator$pollKeys(CallbackInfo callback) {
        // Runs FabricClientBootstrapBase.onClientTick(), which starts by restoring the render context
        // (so a render method that threw between its HEAD and RETURN injection cannot leave a
        // text-input depth above zero and silently disable translation for the session) and then
        // drives the join hint and the runtime status line. The tick hook used to be an empty method,
        // so none of that ran in 1.13.2. The status line is not called a second time here for the same
        // reason; it is part of that tick body now.
        UniversalTranslatorFabricClient.tickFromMixin();
        // require = 0 keeps a renamed tick()V from failing the launch, so the one thing that
        // distinguishes "the hook never applied" from "the keys stopped working" is this line.
        OrnitheClientAccess.noteTickSurface();
        try {
            universalTranslator$pollHotkeys();
        } catch (RuntimeException failure) {
            // A drifted mapping in the hotkey half used to escape through MinecraftClient.tick()V
            // and take the whole client down over one key press.
            OrnitheClientAccess.reportHotkeyFailure(failure);
        } catch (Error failure) {
            OrnitheClientAccess.reportHotkeyFailure(failure);
        }
    }

    /** The hotkey half of the tick injection, isolated so one failure is contained. */
    @Unique
    private void universalTranslator$pollHotkeys() {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) {
            // glfwGetKey on handle 0 reports every key as released and raises a GLFW error on each
            // call whenever an error callback is installed — 20 log lines a second while the hotkeys
            // are dead anyway. Skipping the poll is what the old code should have done; the handle
            // source itself cannot be replaced with MinecraftClient#getWindow() here because this
            // mapping set has no Window class (see InGameHudStatusIndicatorMixin's class javadoc).
            return;
        }
        boolean u = GLFW.glfwGetKey(window, HotkeyKeys.settingsCode()) == GLFW.GLFW_PRESS;
        boolean f8 = GLFW.glfwGetKey(window, HotkeyKeys.toggleCode()) == GLFW.GLFW_PRESS;
        if (u && !universalTranslator$uHeld) {
            UniversalTranslatorFabricClient.openSettingsFromKey();
        }
        if (f8 && !universalTranslator$f8Held) {
            UniversalTranslatorFabricClient.toggleFromKey();
        }
        universalTranslator$uHeld = u;
        universalTranslator$f8Held = f8;
    }
}
