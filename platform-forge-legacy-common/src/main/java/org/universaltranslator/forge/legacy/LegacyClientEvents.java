package org.universaltranslator.forge.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationStatusLocalizer;

import java.io.File;

/** Forge 1.8.9/1.12.2 compatible key binding and settings-screen launcher. */
public final class LegacyClientEvents {
    private static final long FAILURE_NOTIFICATION_COOLDOWN_MILLIS = 60_000L;
    private static final LegacyClientEvents INSTANCE = new LegacyClientEvents();
    private static final KeyBinding OPEN_SETTINGS = new KeyBinding(
            "key.universal_translator.open_settings", Keyboard.KEY_U, "MC Auto Translation Tool");
    private static final KeyBinding TOGGLE_TRANSLATION = new KeyBinding(
            "key.universal_translator.toggle_translation", Keyboard.KEY_F8, "MC Auto Translation Tool");
    private static File configDirectory;
    private static boolean registered;
    private boolean connectedLastTick;
    private int joinHintTicks = -1;
    private String lastRuntimeStatus = "";
    private long nextFailureNotificationAt;

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
        if (message.isEmpty()) {
            return;
        }
        if (!LegacyTranslationRuntime.shouldTranslateOutgoing(message)) {
            LegacyTranslationRuntime.protectOutgoingMessage(message);
            return;
        }
        event.setCanceled(true);
        Minecraft minecraft = Minecraft.getMinecraft();
        LegacyVersionAccess.rememberSentMessage(minecraft, message);
        minecraft.displayGuiScreen(null);
        minecraft.setIngameFocus();
        minecraft.ingameGUI.setRecordPlayingMessage(
                tr("message.universal_translator.outgoing_translating"));
        LegacyTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                minecraft.addScheduledTask(() -> sendCompletedMessage(
                        minecraft, message, result, error)));
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
                    tr("message.universal_translator.join_hint"));
            long maximumMemoryMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            if (maximumMemoryMiB < 768L) {
                LegacyVersionAccess.showLocalChatMessage(minecraft,
                        tr("message.universal_translator.memory_warning", maximumMemoryMiB));
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
                lastRuntimeStatus = "";
                nextFailureNotificationAt = 0L;
                updated.save();
                minecraft.ingameGUI.setRecordPlayingMessage(
                        tr("message.universal_translator.toggle", tr(updated.enabled
                                ? "value.universal_translator.enabled"
                                : "value.universal_translator.disabled")));
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
        notifyRuntimeStatus(minecraft, connected);
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

    private void notifyRuntimeStatus(Minecraft minecraft, boolean connected) {
        String current = connected ? LegacyTranslationRuntime.status() : "";
        if (current == null) {
            current = "";
        }
        if (current.equals(lastRuntimeStatus)) {
            return;
        }
        lastRuntimeStatus = current;
        if (current.isEmpty()) {
            nextFailureNotificationAt = 0L;
            return;
        }
        String localized = TranslationStatusLocalizer.localize(current, LegacyClientEvents::tr);
        if (isFailureStatus(current)) {
            long now = System.currentTimeMillis();
            if (now < nextFailureNotificationAt) {
                return;
            }
            nextFailureNotificationAt = now + FAILURE_NOTIFICATION_COOLDOWN_MILLIS;
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    tr("message.universal_translator.runtime_failed", localized));
        } else {
            nextFailureNotificationAt = 0L;
            minecraft.ingameGUI.setRecordPlayingMessage(
                    tr("message.universal_translator.runtime_status", localized));
        }
    }

    private static boolean isFailureStatus(String status) {
        return TranslationStatusLocalizer.isFailure(status);
    }

    private static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }

    private static void sendCompletedMessage(
            Minecraft minecraft,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        if (LegacyVersionAccess.connection(minecraft) == null) {
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    tr("message.universal_translator.outgoing_disconnected"));
            return;
        }
        boolean failed = error != null || result == null || result.isFailure();
        String outgoing = failed || !result.isTranslated()
                ? original : result.getTranslatedText();
        boolean tooLong = outgoing.length() > LegacyVersionAccess.maximumChatLength();
        if (tooLong) {
            outgoing = original;
        }
        LegacyTranslationRuntime.protectOutgoingMessage(outgoing);
        LegacyVersionAccess.sendChatMessage(minecraft, outgoing);
        if (failed) {
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    tr("message.universal_translator.outgoing_failed"));
        } else if (tooLong) {
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    tr("message.universal_translator.outgoing_too_long"));
        }
    }
}
