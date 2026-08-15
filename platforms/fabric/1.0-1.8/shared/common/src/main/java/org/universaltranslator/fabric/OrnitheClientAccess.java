package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.TextRenderer;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class OrnitheClientAccess {
    private static final ConcurrentLinkedQueue<Runnable> CLIENT_TASKS =
            new ConcurrentLinkedQueue<Runnable>();

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

    public static String tr(String key, Object... arguments) {
        try {
            Class<?> i18n = Class.forName("net.minecraft.client.resource.language.I18n");
            return (String) i18n.getMethod("translate", String.class, Object[].class)
                    .invoke(null, key, arguments);
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
        try {
            Class<?> i18n = Class.forName("net.minecraft.locale.I18n");
            try {
                return (String) i18n.getMethod("translate", String.class, Object[].class)
                        .invoke(null, key, arguments);
            } catch (NoSuchMethodException ignored) {
                Object translated = i18n.getMethod("translate", String.class).invoke(null, key);
                if (arguments == null || arguments.length == 0) {
                    return String.valueOf(translated);
                }
                return String.format(String.valueOf(translated), arguments);
            }
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    public static void overlay(String key, Object... arguments) {
        String message = tr(key, arguments);
        Object gui = client().gui;
        try {
            gui.getClass().getMethod("setOverlayMessage", String.class, boolean.class)
                    .invoke(gui, message, false);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
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
                System.err.println("[MC Auto Translation Tool] Client task failed: " + exception);
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

    private static void addChatMessage(String message) {
        Object chat = client().gui.getChat();
        try {
            Class<?> literalText = Class.forName("net.minecraft.text.LiteralText");
            Object text = literalText.getConstructor(String.class).newInstance(message);
            try {
                chat.getClass().getMethod("addMessage", literalText).invoke(chat, text);
            } catch (NoSuchMethodException ignored) {
                chat.getClass().getMethod("addMessage", Class.forName("net.minecraft.text.Text"))
                        .invoke(chat, text);
            }
            return;
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
        try {
            chat.getClass().getMethod("addMessage", String.class).invoke(chat, message);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    static int maximumChatLength() {
        return 100;
    }
}
