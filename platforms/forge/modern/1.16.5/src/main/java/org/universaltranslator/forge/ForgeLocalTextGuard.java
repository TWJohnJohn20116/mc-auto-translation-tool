package org.universaltranslator.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import org.universaltranslator.core.TranslationTextStyling;

import java.lang.reflect.Field;

/** Identifies text currently being edited by the local player in the chat box. */
final class ForgeLocalTextGuard {
    private static volatile Field chatField;
    private static volatile boolean searched;

    private ForgeLocalTextGuard() {
    }

    static boolean isLocalChatInput(Minecraft client, String rendered) {
        if (client == null || !(client.screen instanceof ChatScreen)
                || rendered == null || rendered.isEmpty()) {
            return false;
        }
        TextFieldWidget field = findChatField(client.screen);
        return field != null && matches(field.getValue(), rendered);
    }

    private static TextFieldWidget findChatField(Screen screen) {
        Field known = chatField;
        if (!searched) {
            synchronized (ForgeLocalTextGuard.class) {
                if (!searched) {
                    chatField = findTextField(screen.getClass());
                    searched = true;
                }
                known = chatField;
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

    private static boolean matches(String typed, String rendered) {
        if (typed == null || typed.isEmpty()) {
            return false;
        }
        String visible = TranslationTextStyling.stripLegacyFormatting(rendered);
        if (visible.equals(typed) || visible.equals(typed + "_")) {
            return true;
        }
        return visible.length() >= 2 && typed.contains(visible);
    }
}
