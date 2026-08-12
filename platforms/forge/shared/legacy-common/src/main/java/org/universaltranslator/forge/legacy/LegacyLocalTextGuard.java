package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.universaltranslator.core.TranslationTextStyling;

import java.lang.reflect.Field;

/** Identifies text currently being edited by the local player in the legacy chat box. */
final class LegacyLocalTextGuard {
    private static volatile Field chatField;
    private static volatile boolean searched;

    private LegacyLocalTextGuard() {
    }

    static boolean isLocalChatInput(GuiScreen screen, String rendered) {
        if (!(screen instanceof GuiChat) || rendered == null || rendered.isEmpty()) {
            return false;
        }
        GuiTextField field = findChatField(screen);
        return field != null && matches(field.getText(), rendered);
    }

    static String currentChatInput(GuiScreen screen) {
        if (!(screen instanceof GuiChat)) {
            return "";
        }
        GuiTextField field = findChatField(screen);
        String text = field == null ? null : field.getText();
        return text == null ? "" : text.trim();
    }

    private static GuiTextField findChatField(GuiScreen screen) {
        Field known = chatField;
        if (!searched) {
            synchronized (LegacyLocalTextGuard.class) {
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
            return value instanceof GuiTextField ? (GuiTextField) value : null;
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findTextField(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (GuiTextField.class.isAssignableFrom(field.getType())) {
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
