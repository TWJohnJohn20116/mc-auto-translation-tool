package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.TextRenderer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.universaltranslator.core.DebugLog;

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

    private OrnitheClientAccess() {
    }

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
        Method setOverlay = OverlayAccess.RESOLVED.setOverlay(gui);
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
        Runnable task;
        while ((task = CLIENT_TASKS.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException exception) {
                // Through the mod's logger, so the line gets a level and can be filtered; stdout had
                // neither and launch wrappers showed it as a plain error line.
                LOGGER.log(Level.SEVERE, "Client task failed", exception);
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
            try {
                return (String) entry.getClass().getField("ip").get(entry);
            } catch (NoSuchFieldException ignored) {
                return (String) entry.getClass().getField("address").get(entry);
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
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
        ChatAccess.RESOLVED.send(message);
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

    /** The {@code I18n} entry point this Minecraft version exposes, resolved at most once. */
    private static final class TranslateAccess {
        private static final TranslateAccess RESOLVED = resolve();

        private final Method method;
        private final boolean formatsArguments;

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
            try {
                if (!formatsArguments) {
                    return (String) method.invoke(null, key, arguments);
                }
                Object translated = method.invoke(null, key);
                if (arguments == null || arguments.length == 0) {
                    return String.valueOf(translated);
                }
                return String.format(String.valueOf(translated), arguments);
            } catch (ReflectiveOperationException exception) {
                reportOnce(TRANSLATE_FALLBACK_REPORTED, "I18n.translate", exception);
                return key;
            } catch (LinkageError error) {
                reportOnce(TRANSLATE_FALLBACK_REPORTED, "I18n.translate", error);
                return key;
            }
        }
    }

    /** The overlay-message method of this version's HUD, resolved at most once. */
    private static final class OverlayAccess {
        private static final OverlayAccess RESOLVED = resolve();

        private final Method setOverlay;

        private OverlayAccess(Method setOverlay) {
            this.setOverlay = setOverlay;
        }

        private static OverlayAccess resolve() {
            Minecraft client = Minecraft.getInstance();
            Object gui = client == null ? null : client.gui;
            if (gui == null) {
                return new OverlayAccess(null);
            }
            try {
                return new OverlayAccess(
                        gui.getClass().getMethod("setOverlayMessage", String.class, boolean.class));
            } catch (NoSuchMethodException ignored) {
                return new OverlayAccess(null);
            } catch (LinkageError error) {
                return new OverlayAccess(null);
            }
        }

        Method setOverlay(Object gui) {
            return gui == null ? null : setOverlay;
        }
    }

    /** This version's chat HUD sink, resolved at most once. */
    private static final class ChatAccess {
        private static final ChatAccess RESOLVED = resolve();

        private final Method getChat;
        private final Constructor<?> literal;
        private final Method addMessage;

        private ChatAccess(Method getChat, Constructor<?> literal, Method addMessage) {
            this.getChat = getChat;
            this.literal = literal;
            this.addMessage = addMessage;
        }

        private static ChatAccess resolve() {
            Minecraft client = Minecraft.getInstance();
            Object gui = client == null ? null : client.gui;
            if (gui == null) {
                return new ChatAccess(null, null, null);
            }
            try {
                Method getChat = gui.getClass().getMethod("getChat");
                Object chat = getChat.invoke(gui);
                if (chat == null) {
                    return new ChatAccess(getChat, null, null);
                }
                Class<?> chatType = chat.getClass();
                try {
                    Class<?> literalType = Class.forName("net.minecraft.text.LiteralText");
                    Constructor<?> literal = literalType.getConstructor(String.class);
                    try {
                        return new ChatAccess(getChat, literal,
                                chatType.getMethod("addMessage", literalType));
                    } catch (NoSuchMethodException ignored) {
                        return new ChatAccess(getChat, literal, chatType.getMethod(
                                "addMessage", Class.forName("net.minecraft.text.Text")));
                    }
                } catch (ClassNotFoundException ignored) {
                    // No component type at all: fall through to the plain-string overload.
                } catch (NoSuchMethodException ignored) {
                }
                return new ChatAccess(getChat, null, chatType.getMethod("addMessage", String.class));
            } catch (ReflectiveOperationException exception) {
                return new ChatAccess(null, null, null);
            } catch (LinkageError error) {
                return new ChatAccess(null, null, null);
            }
        }

        void send(String message) {
            if (addMessage == null || getChat == null) {
                reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud.addMessage", null);
                return;
            }
            try {
                Minecraft client = Minecraft.getInstance();
                Object gui = client == null ? null : client.gui;
                Object chat = gui == null ? null : getChat.invoke(gui);
                if (chat == null) {
                    reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud", null);
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
