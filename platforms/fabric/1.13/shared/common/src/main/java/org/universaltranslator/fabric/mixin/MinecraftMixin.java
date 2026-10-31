package org.universaltranslator.fabric.mixin;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
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

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void universalTranslator$pollKeys(CallbackInfo callback) {
        // Runs FabricClientBootstrapBase.onClientTick(), which starts by restoring the render context
        // (so a render method that threw between its HEAD and RETURN injection cannot leave a
        // text-input depth above zero and silently disable translation for the session) and then
        // drives the join hint and the runtime status line. Running the reset and the status line
        // here as well would do both twice per tick.
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
        Minecraft client = (Minecraft) (Object) this;
        if (client.window == null) {
            return;
        }
        long window = client.window.getWindow();
        // A handle of 0 means GLFW has no current context, which happens while the window is being
        // recreated and for the first ticks after a full-screen toggle: glfwGetKey then answers
        // GLFW_RELEASE for every key, so the hotkeys went silent with nothing in the log.
        boolean u = false;
        boolean f8 = false;
        if (window != 0L) {
            u = GLFW.glfwGetKey(window, HotkeyKeys.settingsCode()) == GLFW.GLFW_PRESS;
            f8 = GLFW.glfwGetKey(window, HotkeyKeys.toggleCode()) == GLFW.GLFW_PRESS;
        }
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
