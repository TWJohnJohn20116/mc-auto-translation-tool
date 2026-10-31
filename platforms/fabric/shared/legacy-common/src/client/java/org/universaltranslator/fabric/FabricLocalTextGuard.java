package org.universaltranslator.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.universaltranslator.core.TranslationTextStyling;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identifies text currently being edited by the local player in the chat box.
 *
 * <p>Keep in sync with {@code shared/guard-legacy/…/FabricLocalTextGuard.java}: the bodies are the
 * same and differ only in the client accessors ({@code MinecraftClient.currentScreen} here,
 * {@code Minecraft.screen} there). Collapsing the pair needs a seam for those two calls plus the
 * per-version {@code srcDir} wiring, which is audit2 S2-078 and outside this layer.
 */
final class FabricLocalTextGuard {
    /**
     * The text field of the chat box, together with the screen class it was resolved for.
     *
     * <p>Two separate volatile fields could be observed half-updated — the class of the screen being
     * drawn next to the field of a different screen type — and {@code Field.get} then threw
     * {@code IllegalArgumentException}, which the caller swallowed, so that frame's local-input
     * check silently failed. One immutable holder publishes both or neither.
     */
    private static volatile Resolved resolved;

    /** One resolution: the screen class it is valid for and the field found on it. */
    private static final class Resolved {
        private final Class<?> type;
        private final Field field;

        private Resolved(Class<?> type, Field field) {
            this.type = type;
            this.field = field;
        }
    }

    /** Last text the chat box held, with the two cursor forms the renderer appends to it. */
    private static volatile String cachedTyped;
    private static volatile String cachedTypedUnderscore;
    private static volatile String cachedTypedPipe;
    /**
     * Stripped lines, most recently used last.
     *
     * <p>One slot was not enough: the chat window draws a hundred lines in a fixed order, so a memo
     * keyed on "the previous line" missed on every single line of every frame and still paid the
     * lookup. Bounded because those lines are player and server text.
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
        Resolved known = resolved;
        if (known == null || known.type != type) {
            synchronized (FabricLocalTextGuard.class) {
                known = resolved;
                if (known == null || known.type != type) {
                    known = new Resolved(type, findTextField(type));
                    resolved = known;
                }
            }
        }
        Field field = known.field;
        // The screen must really be an instance of the class the field was resolved for: a pair
        // published for another screen type would throw instead of answering.
        if (field == null || !field.getDeclaringClass().isInstance(screen)) {
            return null;
        }
        try {
            Object value = field.get(screen);
            return value instanceof TextFieldWidget ? (TextFieldWidget) value : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findTextField(Class<?> type) {
        // Names first. {@code getDeclaredFields()} has no defined order, so a screen that carries a
        // second text field — a search box, a third-party mixin's input — could be picked instead of
        // the one the player types into, and that wrong choice was then cached for the session.
        String[] preferred = {"chatField", "input", "textField"};
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (String name : preferred) {
                Field field = declaredField(current, name);
                if (field != null) {
                    return field;
                }
            }
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (TextFieldWidget.class.isAssignableFrom(field.getType()) && makeAccessible(field)) {
                    return field;
                }
            }
        }
        return null;
    }

    /** A declared field of {@code holder} with the given name, when it holds a text field. */
    private static Field declaredField(Class<?> holder, String name) {
        try {
            Field field = holder.getDeclaredField(name);
            if (TextFieldWidget.class.isAssignableFrom(field.getType()) && makeAccessible(field)) {
                return field;
            }
        } catch (NoSuchFieldException | RuntimeException ignored) {
            // Not this class, or not reachable: keep looking.
        }
        return null;
    }

    /** True when the field can be read; one refusal must not abandon the whole search. */
    private static boolean makeAccessible(Field field) {
        try {
            field.setAccessible(true);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
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
        // The candidates were rebuilt from the typed text on every call, so a hundred visible chat
        // lines cost up to three hundred concatenations per frame while the player was typing.
        // They only change when the field does, so they are derived once per distinct value; the
        // two candidate fields are published before the key they belong to.
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
     * {@code rendered} without its legacy colour codes, memoized for the lines of this frame.
     *
     * <p>A hit costs one lookup instead of a full walk plus a copy; the map keeps the sixty-four
     * most recently drawn lines, which covers the visible chat window without growing with it.
     */
    private static String stripped(String rendered) {
        LinkedHashMap<String, String> memo = STRIPPED.get();
        String cached = memo.get(rendered);
        if (cached != null) {
            return cached;
        }
        String value = TranslationTextStyling.stripLegacyFormatting(rendered);
        memo.put(rendered, value);
        return value;
    }
}
