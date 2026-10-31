package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.locale.I18n;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
     *
     * <p>{@code hideGui} is the name this bundle's ploceus (feather) mappings give the flag on every
     * version it compiles, 1.3.1 through 1.7.10; {@code hudHidden} is the yarn name that arrives with
     * 1.14.
     */
    public static boolean hudHidden(Minecraft client) {
        if (client == null || client.options == null) {
            return false;
        }
        return client.options.hideGui;
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
        OrnitheFamilyAccess.setEditable(field, editable);
    }

    /**
     * Translates a key through this generation's language table.
     *
     * <p>A direct call, not a lookup by name: {@code remapJar} rewrites references but not string
     * literals, so {@code Class.forName("net.minecraft...")} answered "not found" in the shipped JAR
     * and every label fell back to its own key. {@code net.minecraft.locale.I18n} carries both the
     * plain and the {@code Object[]} overload on all seventeen versions of this bundle, so no
     * generation split is needed for this one.
     */
    public static String tr(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }

    public static void overlay(String key, Object... arguments) {
        String message = tr(key, arguments);
        Minecraft client = client();
        if (client == null || client.gui == null) {
            // Start-up and shutdown leave the HUD unset; the message has nowhere to go and this is
            // not a mapping drift, so it is dropped without a report.
            return;
        }
        if (!OrnitheFamilyAccess.overlayMessage(client, message)) {
            // This generation keeps the line in a private field and exposes no setter; the single
            // report below says so once.
            reportOnce(OVERLAY_FALLBACK_REPORTED, "setOverlayMessage", null);
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

    /**
     * The name this session signed in with.
     *
     * <p>Part of the generation split (see {@code OrnitheFamilyAccess}): 1.3.1-1.5.2 keep the name in
     * a public {@code Session} field and expose no accessor, 1.6.1 and later answer
     * {@code getSession().getUsername()}. Read directly because a lookup by name cannot survive
     * {@code remapJar}, which rewrites references but not string literals.
     */
    static String sessionUsername() {
        Minecraft client = client();
        if (client == null) {
            return null;
        }
        return OrnitheFamilyAccess.username(client);
    }

    /**
     * The address of the server this session plays on, or {@code null}.
     *
     * <p>The last lookup by name in this class, and the one that still cannot succeed in the shipped
     * JAR: {@code remapJar} rewrites references but not string literals, so the runtime classes carry
     * the unmapped names instead of the ones below. The fix is a per-generation call, but it needs
     * three shapes rather than the two layers this tree has: 1.3.1-1.5.2 and 1.7.x expose
     * {@code getCurrentServerEntry()}, while 1.6.1/1.6.4 keep the entry in a private
     * {@code currentServerEntry} field with no getter and share the 1.6-1.8 layer with 1.7.x. Left in
     * place, still told apart from "not on a server" by the one-shot report below.
     */
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
     * <p>The component type is the only thing that moved inside this bundle: 1.3.1-1.6.4 append a
     * plain {@code String}, 1.7.x want a {@code Text} ({@code OrnitheChatSink} carries the half each
     * one compiles). A failure drops the line and is reported once, because this runs from tick and
     * render paths whose callers never expect an exception and a drifted mapping surfaces as an
     * {@link Error}.
     */
    private static void addChatMessage(String message) {
        Minecraft client = client();
        if (client == null || client.gui == null) {
            // Start-up and shutdown leave the HUD unset: nothing to append the line to, and this is
            // not a mapping drift.
            return;
        }
        try {
            OrnitheChatSink.addMessage(client, message);
        } catch (LinkageError error) {
            reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud.addMessage", error);
        } catch (NullPointerException error) {
            // The chat HUD itself is not built yet: nothing to append the line to.
            reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud.addMessage", error);
        }
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
}
