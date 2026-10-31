package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TranslationTextStyling;

import java.lang.reflect.Field;

/**
 * Identifies text currently being edited by the local player in the chat box.
 *
 * <p>Every screen lookup goes through {@link FabricScreenOpener#currentScreen}, which is the only
 * place that knows 26.1 reads {@code Minecraft#screen} and 26.2+ reads {@code Minecraft#gui}. The
 * body below is therefore identical to the {@code 26.2-26.3} copy; the two still live in separate
 * layers because they are compiled against different Minecraft versions, but a change here has to be
 * mirrored there by hand.
 */
final class FabricLocalTextGuard {
    /** Field names a chat screen's input box is known by, mojmap first. */
    private static final String[] CHAT_FIELD_NAMES = {"input", "chatField"};
    private static volatile Field chatField;
    /**
     * The screen class {@link #chatField} was resolved for, or {@code null} before the first try.
     *
     * <p>Latching only a successful lookup meant a chat screen whose field could not be found
     * re-walked the whole class hierarchy on every frame, from the render thread. Recording the
     * class makes a failed lookup cost one scan per screen type.
     */
    private static volatile Class<?> resolvedFor;
    /** Last text the chat box held, with the two cursor forms the renderer appends to it. */
    private static volatile String cachedTyped;
    private static volatile String cachedTypedUnderscore;
    private static volatile String cachedTypedPipe;
    /** Last stripped line, kept so the same drawn line is not re-walked for every hook. */
    private static volatile String strippedSource;
    private static volatile String strippedValue;

    private FabricLocalTextGuard() {
    }

    static Object currentScreen(Minecraft client) {
        return FabricScreenOpener.currentScreen(client);
    }

    static boolean isLocalChatInput(Minecraft client, String rendered) {
        if (client == null || rendered == null || rendered.isEmpty()) {
            return false;
        }
        Screen screen = FabricScreenOpener.currentScreen(client);
        if (!(screen instanceof ChatScreen)) {
            return false;
        }
        EditBox field = findChatField(screen);
        return field != null && matches(field.getValue(), rendered);
    }

    private static EditBox findChatField(Screen screen) {
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
            return value instanceof EditBox ? (EditBox) value : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Finds the chat box among a screen's fields.
     *
     * <p>Field order is not specified by the JVM, so "the first field whose type is an edit box"
     * silently picks the wrong one as soon as the class gains a second. A name match decides first;
     * when several candidates exist and none is named, this declines to guess and says so in the
     * debug log, because guarding the wrong field would send the player's own typing to the
     * provider and rewrite the chat box.
     */
    private static Field findTextField(Class<?> type) {
        Field fallback = null;
        int candidates = 0;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!EditBox.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                candidates++;
                if (isChatFieldName(field.getName())) {
                    try {
                        field.setAccessible(true);
                        return field;
                    } catch (RuntimeException ignored) {
                        return null;
                    }
                }
                if (fallback == null) {
                    fallback = field;
                }
            }
        }
        if (candidates > 1) {
            DebugLog.global().log("local-text-guard", "ambiguous-chat-field",
                    type.getName() + " has " + candidates + " edit boxes");
            return null;
        }
        if (fallback == null) {
            return null;
        }
        try {
            fallback.setAccessible(true);
            return fallback;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isChatFieldName(String name) {
        for (String candidate : CHAT_FIELD_NAMES) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code rendered} is the chat box's own text, cursor included.
     *
     * <p>Only equality counts. Accepting "typed contains rendered" made every other line that
     * happened to be a substring of what the player was typing untranslatable while the chat screen
     * was open: typing {@code hello world} silently skipped the server message {@code world}.
     */
    private static boolean matches(String typed, String rendered) {
        if (typed == null || typed.isEmpty()) {
            return false;
        }
        // The cursor candidates are derived once per distinct typed value instead of two
        // concatenations per drawn line, and the stripped comparison is memoized for the current
        // line.
        String underscore = cachedTypedUnderscore;
        String pipe = cachedTypedPipe;
        if (!typed.equals(cachedTyped)) {
            underscore = typed + "_";
            pipe = typed + "|";
            cachedTypedUnderscore = underscore;
            cachedTypedPipe = pipe;
            cachedTyped = typed;
        }
        String visible = stripped(rendered);
        return visible.equals(typed)
                || visible.equals(underscore)
                || visible.equals(pipe);
    }

    /**
     * {@code rendered} without its legacy colour codes, memoized for the last line seen; the value
     * is published before its source, so a racing reader sees the pair or recomputes.
     */
    private static String stripped(String rendered) {
        String source = strippedSource;
        if (source != null && source.equals(rendered)) {
            return strippedValue;
        }
        String value = TranslationTextStyling.stripLegacyFormatting(rendered);
        strippedValue = value;
        strippedSource = rendered;
        return value;
    }
}
