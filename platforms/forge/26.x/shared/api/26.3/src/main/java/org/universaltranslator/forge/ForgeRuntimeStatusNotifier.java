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
 * <p>This copy targets the 26.2 chat and Action Bar API, which moved behind {@code client.gui.hud},
 * and is shared by the Forge 26.2 module and the NeoForge 26.2 module.</p>
 */
public final class ForgeRuntimeStatusNotifier {
    private static final long FAILURE_NOTIFICATION_COOLDOWN_MILLIS = 60_000L;
    /**
     * Minimum gap between two action-bar updates of a non-failure status.
     *
     * <p>The de-duplication below compares whole strings, and the offline engine reports its download
     * progress in the status string, so the percentage changed every few tens of milliseconds: each
     * change rewrote the action bar and pushed out whatever the player was being shown there.
     */
    private static final long STATUS_NOTIFICATION_INTERVAL_MILLIS = 1_000L;
    private static String lastRuntimeStatus = "";
    private static long nextFailureNotificationAt;
    private static long nextStatusNotificationAt;

    private ForgeRuntimeStatusNotifier() {
    }

    /** Forgets the last reported status so the next status change is announced again. */
    public static void reset() {
        lastRuntimeStatus = "";
        nextFailureNotificationAt = 0L;
        nextStatusNotificationAt = 0L;
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
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.translatable("message.universal_translator.runtime_failed", localized));
        } else {
            nextFailureNotificationAt = 0L;
            long now = System.currentTimeMillis();
            if (now < nextStatusNotificationAt) {
                // Progress-only change inside the throttle window: the next change re-announces.
                return;
            }
            nextStatusNotificationAt = now + STATUS_NOTIFICATION_INTERVAL_MILLIS;
            client.gui.hud.setOverlayMessage(
                    Component.translatable("message.universal_translator.runtime_status", localized), false);
        }
    }

    private static String tr(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }
}
