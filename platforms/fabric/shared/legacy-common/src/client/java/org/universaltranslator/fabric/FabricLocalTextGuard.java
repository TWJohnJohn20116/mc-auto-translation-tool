package org.universaltranslator.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.universaltranslator.core.TranslationTextStyling;

import java.lang.reflect.Field;

/** Identifies text currently being edited by the local player in the chat box. */
final class FabricLocalTextGuard {
    private static volatile Field chatField;
    /**
     * The screen class {@link #chatField} was resolved for, or {@code null} before the first attempt.
     *
     * <p>The previous version latched only a *successful* lookup, so a chat screen whose text field
     * could not be found re-walked the whole class hierarchy on every frame — from the render thread.
     * Recording the class instead makes a failed lookup cost one scan per screen type, and a
     * different screen class (a version's own chat screen, a modded one) re-runs it.
     */
    private static volatile Class<?> resolvedFor;

    private FabricLocalTextGuard() {
    }

    static Object currentScreen(MinecraftClient client) {
        return client == null ? null : client.currentScreen;
    }

    static boolean isLocalChatInput(MinecraftClient client, String rendered) {
        if (client == null || !(client.currentScreen instanceof ChatScreen)
                || rendered == null || rendered.isEmpty()) {
            return false;
        }
        TextFieldWidget field = findChatField(client.currentScreen);
        return field != null && matches(field.getText(), rendered);
    }

    private static TextFieldWidget findChatField(Screen screen) {
        Class<?> type = screen.getClass();
        Field known = chatField;
        if (resolvedFor != type) {
            synchronized (FabricLocalTextGuard.class) {
                if (resolvedFor != type) {
                    known = findTextField(type);
                    chatField = known;
                    resolvedFor = type;
                } else {
                    known = chatField;
                }
            }
        }
        if (known == null) {
            return null;
        }
        try {
            Object value = known.get(screen);
            return value instanceof TextFieldWidget ? (TextFieldWidget) value : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findTextField(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (TextFieldWidget.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return field;
                    } catch (RuntimeException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * True when {@code rendered} is the chat box's own text, cursor included.
     *
     * <p>Only equality counts. The old version also accepted "typed contains rendered", which made
     * every other line that happened to be a substring of what the player was typing untranslatable
     * while the chat screen was open: typing {@code hello world} silently skipped the server message
     * {@code world}.
     */
    private static boolean matches(String typed, String rendered) {
        if (typed == null || typed.isEmpty()) {
            return false;
        }
        String visible = TranslationTextStyling.stripLegacyFormatting(rendered);
        return visible.equals(typed)
                || visible.equals(typed + "_")
                || visible.equals(typed + "|");
    }
}
