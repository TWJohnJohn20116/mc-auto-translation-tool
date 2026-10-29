package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.universaltranslator.core.TranslationTextStyling;

import java.lang.reflect.Field;

/**
 * Identifies text currently being edited by the local player in the chat box.
 *
 * <p>Keep in sync with {@code shared/legacy-common/…/FabricLocalTextGuard.java}: the bodies are the
 * same and differ only in the client accessors ({@code Minecraft.screen} here,
 * {@code MinecraftClient.currentScreen} there). Collapsing the pair needs a seam for those two
 * calls plus the per-version {@code srcDir} wiring, which is audit2 S2-078 and outside this layer.
 */
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
        return client == null ? null : client.screen;
    }

    static boolean isLocalChatInput(Minecraft client, String rendered) {
        if (client == null || !(client.screen instanceof ChatScreen)
                || rendered == null || rendered.isEmpty()) {
            return false;
        }
        TextFieldWidget field = findChatField(client.screen);
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
        // See the copy in shared/legacy-common: the cursor candidates are derived once per distinct
        // typed value instead of three concatenations per drawn line, and the stripped comparison
        // is memoized for the line currently being drawn.
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
