package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.universaltranslator.core.TranslationTextStyling;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

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
    /**
     * Stripped lines, most recently used last; bounded because they are player and server text.
     *
     * <p>One slot was not enough: the chat window draws a hundred lines in a fixed order, so a memo
     * with a single entry missed on nearly every lookup, and every miss rebuilt the whole line.
     */
    private static final int STRIPPED_MEMO_LIMIT = 64;
    private static final ThreadLocal<LinkedHashMap<String, String>> STRIPPED =
            new ThreadLocal<LinkedHashMap<String, String>>() {
                @Override
                protected LinkedHashMap<String, String> initialValue() {
                    return new LinkedHashMap<String, String>(16, 0.75F, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                            return size() > STRIPPED_MEMO_LIMIT;
                        }
                    };
                }
            };
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

    /**
     * Strips legacy colour codes, reusing the answer for the lines of this frame.
     *
     * <p>The chat window draws a hundred lines in a fixed order, so one slot evicted itself between
     * every pair of lines and every lookup rebuilt the whole line.
     */
    private static String stripped(String rendered) {
        LinkedHashMap<String, String> memo = STRIPPED.get();
        String cached = memo.get(rendered);
        if (cached != null) {
            return cached;
        }
        String computed = TranslationTextStyling.stripLegacyFormatting(rendered);
        memo.put(rendered, computed);
        return computed;
    }
}
