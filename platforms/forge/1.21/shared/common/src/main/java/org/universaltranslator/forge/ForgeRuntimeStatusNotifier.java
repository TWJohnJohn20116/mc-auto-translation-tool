package org.universaltranslator.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.TranslationStatusLocalizer;

/**
 * Surfaces provider runtime state in-game so Forge players can see offline model download and
 * startup progress without opening the settings screen. Mirrors the Fabric client behaviour:
 * status changes go to the Action Bar, failures additionally go to chat with a 60 second
 * de-duplication window, and only a changed status is ever reported.
 *
 * <p>Shared by every Forge 1.21.x module and by the NeoForge 1.21.x modules, which reuse the same
 * Forge 1.21 source tree and only replace the two loader entrypoints.</p>
 */
public final class ForgeRuntimeStatusNotifier {
    private static final long FAILURE_NOTIFICATION_COOLDOWN_MILLIS = 60_000L;
    private static String lastRuntimeStatus = "";
    private static long nextFailureNotificationAt;

    private ForgeRuntimeStatusNotifier() {
    }

    /** Forgets the last reported status so the next status change is announced again. */
    public static void reset() {
        lastRuntimeStatus = "";
        nextFailureNotificationAt = 0L;
    }

    /** Reports the current provider status; call once per client tick with the current connection state. */
    public static void tick(Minecraft client, boolean connected) {
        if (client == null) {
            return;
        }
        String current = connected ? ForgeTranslationRuntime.status() : "";
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
        String localized = TranslationStatusLocalizer.localize(current, ForgeRuntimeStatusNotifier::tr);
        if (TranslationStatusLocalizer.isFailure(current)) {
            long now = System.currentTimeMillis();
            if (now < nextFailureNotificationAt) {
                return;
            }
            nextFailureNotificationAt = now + FAILURE_NOTIFICATION_COOLDOWN_MILLIS;
            client.gui.getChat().addMessage(
                    Component.translatable("message.universal_translator.runtime_failed", localized));
        } else {
            nextFailureNotificationAt = 0L;
            client.gui.setOverlayMessage(
                    Component.translatable("message.universal_translator.runtime_status", localized), false);
        }
    }

    private static String tr(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }
}
