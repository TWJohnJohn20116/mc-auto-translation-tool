package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.TextRenderer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.HotkeyKeys;

public final class OrnitheClientAccess {
    private static final Logger LOGGER = Logger.getLogger(UniversalTranslatorFabricClient.MOD_ID);
    private static final ConcurrentLinkedQueue<Runnable> CLIENT_TASKS =
            new ConcurrentLinkedQueue<Runnable>();
    /**
     * One-shot switches for the reflective fallbacks below: a mapping drift must be reported once,
     * not once per rendered frame, and the lookup itself must not be retried per call.
     */
    private static final AtomicBoolean TRANSLATE_FALLBACK_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean OVERLAY_FALLBACK_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean CHAT_FALLBACK_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean SERVER_ADDRESS_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean HOTKEY_FALLBACK_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean HOTKEY_FAILURE_REPORTED = new AtomicBoolean();

    private OrnitheClientAccess() {
    }

    /** Field names the server entry has carried across this range; first match wins. */
    private static final String[] SERVER_ADDRESS_FIELDS = {"ip", "address", "serverAddress"};

    public static Minecraft client() {
        return Minecraft.getInstance();
    }

    public static TextRenderer textRenderer() {
        return client().textRenderer;
    }

    public static void openScreen(Screen screen) {
        client().openScreen(screen);
    }

    /**
     * True when F1 has hidden the HUD.
     *
     * <p>The only read of the flag in this tree: the name has moved between generations (26.x no
     * longer exposes it on the options object at all), and this tree is compiled by the scheduled
     * legacy-targets run rather than by every pull request, so a name that no longer matches should
     * cost one line here instead of one call site per HUD hook.
     */
    public static boolean hudHidden(Minecraft client) {
        if (client == null || client.options == null) {
            return false;
        }
        return client.options.hudHidden;
    }

    /**
     * Sets whether {@code field} accepts typing.
     *
     * <p>The settings screen takes the keystrokes away while the model catalog covers the form; the
     * switch is reached through here so that "this generation's text field" stays in the one class
     * that already owns the version-specific widget API.
     */
    public static void setEditable(TextFieldWidget field, boolean editable) {
        if (field == null) {
            return;
        }
        field.setEditable(editable);
    }

    /**
     * Translates a key, answering the key itself when this Minecraft version's {@code I18n} cannot
     * be reached.
     *
     * <p>The entry point is resolved once ({@link TranslateAccess}) instead of through
     * {@code Class.forName} plus {@code getMethod} on every call: the settings, LLM and diagnostics
     * screens call this for every visible label on every frame, so the lookup used to run hundreds
     * of times a second for strings that never change. A resolution or invocation failure is
     * answered with the key and reported once, never thrown: this is called from
     * {@code Screen.render}.
     */
    public static String tr(String key, Object... arguments) {
        return TranslateAccess.RESOLVED.apply(key, arguments);
    }

    public static void overlay(String key, Object... arguments) {
        String message = tr(key, arguments);
        Object gui = client().gui;
        Method setOverlay = OverlayAccess.current().setOverlay(gui);
        if (setOverlay == null) {
            // This Minecraft version exposes no overlay method under this name/signature; the
            // status message is dropped, and the single report below says so once.
            reportOnce(OVERLAY_FALLBACK_REPORTED, "setOverlayMessage", null);
            return;
        }
        try {
            setOverlay.invoke(gui, message, false);
        } catch (ReflectiveOperationException exception) {
            reportOnce(OVERLAY_FALLBACK_REPORTED, "setOverlayMessage", exception);
        } catch (LinkageError error) {
            reportOnce(OVERLAY_FALLBACK_REPORTED, "setOverlayMessage", error);
        }
    }

    public static void chat(String key, Object... arguments) {
        addChatMessage(tr(key, arguments));
    }

    public static void execute(Runnable task) {
        CLIENT_TASKS.add(task);
    }

    public static void runClientTasks() {
        // Every failure is contained per task, so one bad task cannot discard the rest of the batch
        // or escape into Minecraft.tick().
        Runnable task;
        while ((task = CLIENT_TASKS.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException exception) {
                // Through the mod's logger, so the line gets a level and can be filtered; stdout had
                // neither and launch wrappers showed it as a plain error line.
                LOGGER.log(Level.SEVERE, "Client task failed", exception);
            } catch (Error error) {
                // Errors (LinkageError from a drifted mapping above all) used to escape into
                // Minecraft.tick() and take the whole tick down after the queue was already empty.
                LOGGER.log(Level.SEVERE, "Client task failed", error);
                DebugLog.global().log("client-task",
                        "result", "failed",
                        "failure", error.getClass().getName());
            }
        }
    }

    static String sessionUsername() {
        Minecraft client = client();
        try {
            Object session = Minecraft.class.getMethod("getSession").invoke(client);
            if (session == null) {
                return null;
            }
            try {
                return (String) session.getClass().getMethod("getUsername").invoke(session);
            } catch (NoSuchMethodException ignored) {
                return (String) session.getClass().getMethod("getPlayerName").invoke(session);
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    static String currentServerAddress() {
        Minecraft client = client();
        try {
            Method getter = Minecraft.class.getMethod("getCurrentServerEntry");
            Object entry = getter.invoke(client);
            if (entry == null) {
                return null;
            }
            for (int index = 0; index < SERVER_ADDRESS_FIELDS.length; index++) {
                Field field = findField(entry.getClass(), SERVER_ADDRESS_FIELDS[index]);
                if (field == null) {
                    continue;
                }
                Object value = field.get(entry);
                if (value instanceof String) {
                    return (String) value;
                }
            }
            // Reachable only when the entry carries no field under either name: a mapping drift
            // that used to look exactly like "not on a server", silencing the per-server setting.
            reportOnce(SERVER_ADDRESS_REPORTED, "ServerInfo.address", null);
            return null;
        } catch (ReflectiveOperationException ignored) {
            reportOnce(SERVER_ADDRESS_REPORTED, "ServerInfo.address", ignored);
            return null;
        }
    }

    /**
     * Finds a field declared anywhere on the class chain.
     *
     * <p>{@code Class.getField} only answers public fields, and this generation's server entry keeps
     * the address in a plain one, so both lookups threw and the answer was always null.
     */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Declared on a base class in several builds: keep walking up.
            } catch (RuntimeException ignored) {
                // Not openable in this environment; the caller reports the miss once.
                return null;
            }
        }
        return null;
    }

    /**
     * Posts one chat line through this version's chat HUD.
     *
     * <p>The chat accessor, the {@code addMessage} overload and {@code LiteralText}'s constructor
     * are resolved once ({@link ChatAccess}); a failure drops the line and is reported once. This
     * runs from tick and render paths whose callers never expect an exception, and
     * {@code client().gui.getChat()} throws {@link NoSuchMethodError} rather than an
     * {@link Exception} when a mapping drifts.
     */
    private static void addChatMessage(String message) {
        ChatAccess.current().send(message);
    }

    static int maximumChatLength() {
        return 100;
    }

    /**
     * Reports one reflective fallback, and only the first one per call site.
     *
     * <p>Both sinks are used on purpose: the game log shows a warning without the debug switch,
     * while {@code DebugLog} carries the same event with the request-tracing detail when the user
     * turned tracing on. Reporting silently was the original defect, and reporting every call would
     * flood the log from the render thread.
     */
    private static void reportOnce(AtomicBoolean reported, String call, Throwable failure) {
        if (!reported.compareAndSet(false, true)) {
            return;
        }
        LOGGER.log(Level.WARNING, "Universal Translator: " + call
                + " is unavailable on this Minecraft version; the call is skipped from now on", failure);
        DebugLog.global().log("reflection",
                "call", call,
                "result", "unavailable",
                "failure", failure == null ? "none" : failure.getClass().getName());
    }

    /**
     * Records once that a configured hotkey name has no LWJGL2 key code.
     *
     * <p>{@code HotkeyKeys.codeOf} accepts more names than {@code lwjgl2CodeOf} can number, and the
     * poll silently skips a negative code, so the only place a player can find out why the key does
     * nothing in these generations is the debug log the diagnostics screen prints.
     */
    public static void reportHotkey(boolean settingsUnmapped, boolean toggleUnmapped) {
        if (!HOTKEY_FALLBACK_REPORTED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.warning("Universal Translator: hotkey "
                + (settingsUnmapped ? HotkeyKeys.settingsName() : HotkeyKeys.toggleName())
                + " has no LWJGL2 key code on this Minecraft version; the key does nothing here");
        DebugLog.global().log("hotkey",
                "settings-key", HotkeyKeys.settingsName(),
                "settings-key-lwjgl2", settingsUnmapped ? "unmapped" : "ok",
                "toggle-key", HotkeyKeys.toggleName(),
                "toggle-key-lwjgl2", toggleUnmapped ? "unmapped" : "ok",
                "result", "not-polled");
    }

    /**
     * Records once that the hotkey poll threw, and drops the failure.
     *
     * <p>The poll runs from {@code Minecraft.tick()} through a mixin, so a drifted mapping there used
     * to throw out of the vanilla tick loop and take the client down for one key press. Reported once
     * because the poll runs again on the next tick and would otherwise flood the log.
     */
    public static void reportHotkeyFailure(Throwable failure) {
        if (!HOTKEY_FAILURE_REPORTED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.log(Level.SEVERE, "MC Auto Translation Tool hotkey failed", failure);
        DebugLog.global().log("hotkey",
                "result", "failed",
                "failure", failure.getClass().getName());
    }

    /** The {@code I18n} entry point this Minecraft version exposes, resolved at most once. */
    private static final class TranslateAccess {
        /** Upper bound on memoised keys; the screens together use a few dozen labels. */
        private static final int MEMO_LIMIT = 512;
        /** The language resource can be swapped with a screen open, so the memo is discarded. */
        private static final long MEMO_MILLIS = 1000L;

        private static final TranslateAccess RESOLVED = resolve();

        private final Method method;
        private final boolean formatsArguments;
        private final Map<String, String> memo = new HashMap<String, String>();
        private long memoCreatedAt;

        private TranslateAccess(Method method, boolean formatsArguments) {
            this.method = method;
            this.formatsArguments = formatsArguments;
        }

        private static TranslateAccess resolve() {
            Method found = find("net.minecraft.client.resource.language.I18n", true);
            boolean formats = false;
            if (found == null) {
                found = find("net.minecraft.locale.I18n", true);
            }
            if (found == null) {
                // Older builds only carry translate(String) and expect String.format to fill it in.
                found = find("net.minecraft.locale.I18n", false);
                formats = true;
            }
            return new TranslateAccess(found, formats);
        }

        private static Method find(String className, boolean varargs) {
            try {
                Class<?> i18n = Class.forName(className);
                return varargs
                        ? i18n.getMethod("translate", String.class, Object[].class)
                        : i18n.getMethod("translate", String.class);
            } catch (ClassNotFoundException ignored) {
                return null;
            } catch (NoSuchMethodException ignored) {
                return null;
            } catch (LinkageError error) {
                // Class present but unusable (static init failed): treat it as absent, not as fatal.
                return null;
            }
        }

        String apply(String key, Object[] arguments) {
            if (method == null) {
                reportOnce(TRANSLATE_FALLBACK_REPORTED, "I18n.translate", null);
                return key;
            }
            if (arguments == null || arguments.length == 0) {
                return applyPlain(key);
            }
            try {
                if (!formatsArguments) {
                    return (String) method.invoke(null, key, arguments);
                }
                Object translated = method.invoke(null, key);
                return String.format(String.valueOf(translated), arguments);
            } catch (ReflectiveOperationException exception) {
                reportOnce(TRANSLATE_FALLBACK_REPORTED, "I18n.translate", exception);
                return key;
            } catch (LinkageError error) {
                reportOnce(TRANSLATE_FALLBACK_REPORTED, "I18n.translate", error);
                return key;
            }
        }

        /**
         * Answers a label that has no arguments, which is what every button caption and status line
         * asks for. Those ask again on every drawn frame, and a {@link Method#invoke} each time was
         * the last reflective call left on the render path.
         */
        private String applyPlain(String key) {
            long now = System.currentTimeMillis();
            synchronized (memo) {
                if (memo.size() > MEMO_LIMIT || now - memoCreatedAt > MEMO_MILLIS) {
                    memo.clear();
                    memoCreatedAt = now;
                }
                String cached = memo.get(key);
                if (cached != null) {
                    return cached;
                }
                try {
                    String translated = formatsArguments
                            ? String.valueOf(method.invoke(null, key))
                            : (String) method.invoke(null, key);
                    memo.put(key, translated);
                    return translated;
                } catch (ReflectiveOperationException exception) {
                    reportOnce(TRANSLATE_FALLBACK_REPORTED, "I18n.translate", exception);
                    return key;
                } catch (LinkageError error) {
                    reportOnce(TRANSLATE_FALLBACK_REPORTED, "I18n.translate", error);
                    return key;
                }
            }
        }
    }

    /** The overlay-message method of this version's HUD, resolved at most once. */
    private static final class OverlayAccess {
        /** How long a "the HUD does not exist yet" answer is trusted before asking again. */
        private static final long RETRY_MILLIS = 2000L;

        private static volatile OverlayAccess resolved;
        private static volatile long lastAttemptMillis;

        private final Method setOverlay;
        /** True when the answer was "nothing to talk to yet", which is worth asking about again. */
        private final boolean retryable;

        private OverlayAccess(Method setOverlay, boolean retryable) {
            this.setOverlay = setOverlay;
            this.retryable = retryable;
        }

        /**
         * Answers the cached accessor, re-resolving after {@link #RETRY_MILLIS} when the earlier
         * answer was "the HUD is not built yet".
         *
         * <p>Resolution runs once statically otherwise, so a first call made during client start-up
         * used to latch the null answer for the whole session and swallow every later message.
         */
        private static OverlayAccess current() {
            OverlayAccess access = resolved;
            if (isUsable(access)) {
                return access;
            }
            synchronized (OverlayAccess.class) {
                access = resolved;
                if (isUsable(access)) {
                    return access;
                }
                lastAttemptMillis = System.currentTimeMillis();
                access = resolve();
                resolved = access;
                return access;
            }
        }

        private static boolean isUsable(OverlayAccess access) {
            if (access == null) {
                return false;
            }
            return !access.retryable || System.currentTimeMillis() - lastAttemptMillis >= RETRY_MILLIS;
        }

        private static OverlayAccess resolve() {
            Minecraft client = Minecraft.getInstance();
            Object gui = client == null ? null : client.gui;
            if (gui == null) {
                return new OverlayAccess(null, true);
            }
            try {
                return new OverlayAccess(
                        gui.getClass().getMethod("setOverlayMessage", String.class, boolean.class), false);
            } catch (NoSuchMethodException ignored) {
                return new OverlayAccess(null, false);
            } catch (LinkageError error) {
                return new OverlayAccess(null, false);
            }
        }

        Method setOverlay(Object gui) {
            return gui == null ? null : setOverlay;
        }
    }

    /** This version's chat HUD sink, resolved at most once. */
    private static final class ChatAccess {
        /** How long a "the chat HUD does not exist yet" answer is trusted before asking again. */
        private static final long RETRY_MILLIS = 2000L;

        private static volatile ChatAccess resolved;
        private static volatile long lastAttemptMillis;

        private final Method getChat;
        private final Constructor<?> literal;
        private final Method addMessage;
        /** True when the answer was "nothing to talk to yet", which is worth asking about again. */
        private final boolean retryable;

        private ChatAccess(Method getChat, Constructor<?> literal, Method addMessage, boolean retryable) {
            this.getChat = getChat;
            this.literal = literal;
            this.addMessage = addMessage;
            this.retryable = retryable;
        }

        /**
         * Answers the cached sink, re-resolving after {@link #RETRY_MILLIS} when the earlier answer
         * was "the chat HUD is not built yet".
         *
         * <p>Otherwise the first call, made during client start-up, latched a null sink for the whole
         * session: every join notice, hotkey reply and failure report was dropped in silence.
         */
        private static ChatAccess current() {
            ChatAccess access = resolved;
            if (isUsable(access)) {
                return access;
            }
            synchronized (ChatAccess.class) {
                access = resolved;
                if (isUsable(access)) {
                    return access;
                }
                lastAttemptMillis = System.currentTimeMillis();
                access = resolve();
                resolved = access;
                return access;
            }
        }

        private static boolean isUsable(ChatAccess access) {
            if (access == null) {
                return false;
            }
            return !access.retryable || System.currentTimeMillis() - lastAttemptMillis >= RETRY_MILLIS;
        }

        private static ChatAccess resolve() {
            Minecraft client = Minecraft.getInstance();
            Object gui = client == null ? null : client.gui;
            if (gui == null) {
                return new ChatAccess(null, null, null, true);
            }
            try {
                Method getChat = gui.getClass().getMethod("getChat");
                Object chat = getChat.invoke(gui);
                if (chat == null) {
                    return new ChatAccess(getChat, null, null, true);
                }
                Class<?> chatType = chat.getClass();
                try {
                    Class<?> literalType = Class.forName("net.minecraft.text.LiteralText");
                    Constructor<?> literal = literalType.getConstructor(String.class);
                    try {
                        return new ChatAccess(getChat, literal,
                                chatType.getMethod("addMessage", literalType), false);
                    } catch (NoSuchMethodException ignored) {
                        return new ChatAccess(getChat, literal, chatType.getMethod(
                                "addMessage", Class.forName("net.minecraft.text.Text")), false);
                    }
                } catch (ClassNotFoundException ignored) {
                    // No component type at all: fall through to the plain-string overload.
                } catch (NoSuchMethodException ignored) {
                }
                return new ChatAccess(getChat, null, chatType.getMethod("addMessage", String.class), false);
            } catch (ReflectiveOperationException exception) {
                return new ChatAccess(null, null, null, false);
            } catch (LinkageError error) {
                return new ChatAccess(null, null, null, false);
            }
        }

        void send(String message) {
            if (addMessage == null || getChat == null) {
                // A "not built yet" sink is retried by current(); only a drifted mapping is reported.
                if (!retryable) {
                    reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud.addMessage", null);
                }
                return;
            }
            try {
                Minecraft client = Minecraft.getInstance();
                Object gui = client == null ? null : client.gui;
                if (gui == null) {
                    // Start-up or shutdown: the sink will be re-resolved, so this is not a drift.
                    return;
                }
                Object chat = getChat.invoke(gui);
                if (chat == null) {
                    if (!retryable) {
                        reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud", null);
                    }
                    return;
                }
                Object argument = literal == null ? message : literal.newInstance(message);
                addMessage.invoke(chat, argument);
            } catch (ReflectiveOperationException exception) {
                reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud.addMessage", exception);
            } catch (LinkageError error) {
                reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud.addMessage", error);
            }
        }
    }
}
