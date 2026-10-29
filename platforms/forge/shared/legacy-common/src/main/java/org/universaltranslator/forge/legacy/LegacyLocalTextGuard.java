package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.universaltranslator.core.TranslationTextStyling;

import java.lang.reflect.Field;

/** Identifies text currently being edited by the local player in the legacy chat box. */
final class LegacyLocalTextGuard {
    private static volatile Field chatField;
    /**
     * The screen class {@link #chatField} was resolved for.
     *
     * <p>Also latched when the lookup found nothing. Keeping "already searched" for the success case
     * only meant a mapping that no longer exposes a {@code GuiTextField} field reran the whole
     * superclass walk, allocation included, for every string of every frame.
     */
    private static volatile Class<?> resolvedFor;
    /** Cached styling strip: the same rendered line is asked about once per frame. */
    private static String strippedSource;
    private static String strippedValue;
    /**
     * The draft this class last saw, plus its two cursor-marker forms.
     *
     * <p>Rebuilt only when the draft changes: the concatenations ran for every visible string, every
     * frame, while the chat box was open.
     */
    private static String cachedTyped = "";
    private static String cachedTypedUnderscore = "_";
    private static String cachedTypedPipe = "|";

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
        Class<?> type = screen.getClass();
        if (resolvedFor != type) {
            synchronized (LegacyLocalTextGuard.class) {
                if (resolvedFor != type) {
                    chatField = findTextField(type);
                    resolvedFor = type;
                }
            }
        }
        Field known = chatField;
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
        String visible = stripped(rendered);
        if (visible.equals(typed)) {
            return true;
        }
        // The chat box draws a cursor marker after the draft, so the rendered line can be the draft
        // plus one character. These are three exact comparisons, not a substring test: a server
        // message that merely contains the draft is not the player's own text, and treating it as
        // local input left every short chat line permanently untranslated.
        String underscore = cachedTypedUnderscore;
        String pipe = cachedTypedPipe;
        if (!typed.equals(cachedTyped)) {
            underscore = typed + "_";
            pipe = typed + "|";
            // Value before source, so a concurrent reader never sees a marker built from another draft.
            cachedTypedUnderscore = underscore;
            cachedTypedPipe = pipe;
            cachedTyped = typed;
        }
        return visible.equals(underscore) || visible.equals(pipe);
    }

    /** Strips legacy colour codes, reusing the answer for the same rendered line. */
    private static String stripped(String rendered) {
        String value = strippedValue;
        if (value != null && (rendered == strippedSource || rendered.equals(strippedSource))) {
            return value;
        }
        String computed = TranslationTextStyling.stripLegacyFormatting(rendered);
        // Value before source, for the same reason as the draft markers above.
        strippedValue = computed;
        strippedSource = rendered;
        return computed;
    }
}
