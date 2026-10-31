package org.universaltranslator.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.universaltranslator.core.TranslationTextStyling;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** Identifies text currently being edited by the local player in the chat box. */
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
     * Stripped lines, most recently used last; bounded because they are player and server text.
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
        // The screen must really be an instance of the class the field was resolved for.
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
        // Names first; see the copy in shared/guard-legacy. getDeclaredFields() has no defined
        // order, so the first text field of the hierarchy is not necessarily the chat box.
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
     * <p>Only equality counts, as in {@code guard-legacy}. The old version also accepted "typed
     * contains rendered", which made every other line that happened to be a substring of what the
     * player was typing untranslatable while the chat screen was open: typing {@code hello world}
     * silently skipped the server message {@code world}.
     */
    private static boolean matches(String typed, String rendered) {
        if (typed == null || typed.isEmpty()) {
            return false;
        }
        // The cursor candidates are derived once per distinct typed value instead of three
        // concatenations per drawn line, and the stripped comparison is memoized for the line
        // currently being drawn.
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
     * {@code rendered} without its legacy colour codes, memoized for the lines of this frame; see
     * the copy in {@code shared/guard-legacy}.
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
