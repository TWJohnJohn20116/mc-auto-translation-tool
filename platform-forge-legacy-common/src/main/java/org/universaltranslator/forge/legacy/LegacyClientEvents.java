package org.universaltranslator.forge.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

import java.io.File;

/** Forge 1.8.9/1.12.2 compatible key binding and settings-screen launcher. */
public final class LegacyClientEvents {
    private static final LegacyClientEvents INSTANCE = new LegacyClientEvents();
    private static final KeyBinding OPEN_SETTINGS = new KeyBinding(
            "key.universal_translator.open_settings", Keyboard.KEY_U, "MC Auto Translation Tool");
    private static final KeyBinding TOGGLE_TRANSLATION = new KeyBinding(
            "key.universal_translator.toggle_translation", Keyboard.KEY_F8, "MC Auto Translation Tool");
    private static File configDirectory;
    private static boolean registered;
    private boolean connectedLastTick;
    private int joinHintTicks = -1;

    private LegacyClientEvents() {
    }

    static synchronized void initialize(File directory) {
        configDirectory = directory;
        if (!registered) {
            ClientRegistry.registerKeyBinding(OPEN_SETTINGS);
            ClientRegistry.registerKeyBinding(TOGGLE_TRANSLATION);
            MinecraftForge.EVENT_BUS.register(INSTANCE);
            registered = true;
        }
    }

    @SubscribeEvent
    public void onChatKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!Keyboard.getEventKeyState()
                || (Keyboard.getEventKey() != Keyboard.KEY_RETURN
                && Keyboard.getEventKey() != Keyboard.KEY_NUMPADENTER)) {
            return;
        }
        String message = LegacyLocalTextGuard.currentChatInput(Minecraft.getMinecraft().currentScreen);
        if (!message.isEmpty()) {
            LegacyTranslationRuntime.protectOutgoingMessage(message);
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        boolean connected = LegacyVersionAccess.connection(minecraft) != null;
        if (connected && !connectedLastTick) {
            joinHintTicks = 60;
        } else if (!connected) {
            joinHintTicks = -1;
        }
        connectedLastTick = connected;
        if (connected && joinHintTicks > 0 && --joinHintTicks == 0) {
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    "\u00a7b[MC 自动翻译工具] \u00a7f按 U 打开控制面板；按 F8 一键开关翻译。");
            long maximumMemoryMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            if (maximumMemoryMiB < 768L) {
                LegacyVersionAccess.showLocalChatMessage(minecraft,
                        "\u00a7c[MC 自动翻译工具] 当前仅分配 " + maximumMemoryMiB
                                + " MiB 游戏内存，可能卡在加载页；请在启动器中固定为至少 2048 MiB。");
            }
        }
        if (TOGGLE_TRANSLATION.isPressed() && configDirectory != null) {
            LegacyConfig previous = null;
            boolean runtimeChanged = false;
            try {
                previous = LegacyConfig.load(configDirectory);
                LegacyConfig updated = previous.withEnabled(!previous.enabled);
                if (updated.enabled) {
                    updated.validateProviderConfiguration();
                }
                runtimeChanged = true;
                LegacyTranslationRuntime.initialize(updated);
                updated.save();
                minecraft.ingameGUI.setRecordPlayingMessage(
                        "MC 自动翻译工具: " + (updated.enabled ? "已开启" : "已关闭"));
            } catch (Exception exception) {
                if (runtimeChanged && previous != null) {
                    try {
                        LegacyTranslationRuntime.initialize(previous);
                    } catch (Exception restoreFailure) {
                        exception.addSuppressed(restoreFailure);
                    }
                }
                System.err.println("[MC Auto Translation Tool] Could not toggle translation: " + exception);
            }
        }
        if (!OPEN_SETTINGS.isPressed()) {
            return;
        }
        if (minecraft.currentScreen instanceof LegacyConfigScreen || configDirectory == null) {
            return;
        }
        try {
            LegacyConfig config = LegacyConfig.load(configDirectory);
            minecraft.displayGuiScreen(new LegacyConfigScreen(minecraft.currentScreen, config));
        } catch (Exception exception) {
            System.err.println("[MC Auto Translation Tool] Could not open settings: " + exception);
        }
    }
}
