package org.universaltranslator.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.LogMessages;

/** Forge client lifecycle and input integration. */
public final class UniversalTranslatorForgeClient {
    private static final KeyMapping RELOAD_SETTINGS = new KeyMapping(
            "key.universal_translator.open_settings",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U,
            "key.categories.misc");
    private static final KeyMapping TOGGLE_TRANSLATION = new KeyMapping(
            "key.universal_translator.toggle_translation",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "key.categories.misc");
    private static boolean connectedLastTick;
    private static int joinHintTicks = -1;
    private static boolean resendingTranslatedMessage;

    private UniversalTranslatorForgeClient() {
    }

    /** Whether the shutdown hook that flushes queued configuration writes is already registered. */
    private static boolean configFlushHookInstalled;

    /**
     * Gives the queued configuration writes a bounded moment to land when the process ends.
     *
     * <p>Only a failed startup ever called {@code ForgeTranslationRuntime.shutdown()}, so the writer's
     * own flush never ran: "press F8 and quit" dropped the toggle, and the session and the screen
     * worker threads outlived the configuration they belonged to. A shutdown hook is the one seam
     * every supported loader and game version has without naming a version-specific stopping event.
     */
    private static void installConfigFlushHook() {
        if (configFlushHookInstalled) {
            return;
        }
        configFlushHookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    // The hook only starts the teardown and waits for it with a bound: shutdown()
                    // drains the configuration writer and then closes the session, which waits for
                    // workers parked in a provider read, so running it inline made "close the game"
                    // wait for all of it with no upper limit.
                    Thread teardown = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            ForgeTranslationRuntime.shutdown();
                        }
                    }, "universal-translator-stop");
                    teardown.setDaemon(false);
                    teardown.start();
                    try {
                        teardown.join(
                                org.universaltranslator.core.ShutdownBudget.TEARDOWN_JOIN_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "universal-translator-config-flush"));
        } catch (IllegalStateException alreadyStopping) {
            // The JVM is already shutting down; no thread is left for a queued write to land on.
            configFlushHookInstalled = false;
        }
    }

    private static void loadConfig() {
        installConfigFlushHook();
        // Registered before the configuration is loaded on purpose: the title-screen entry performs
        // its own configuration read when clicked, so it must not depend on this initialization
        // succeeding. Registering it after initialize() left the entry a dead button for the whole
        // session whenever the load threw.
        ForgeTranslationRuntime.setTitleSettingsOpener(UniversalTranslatorForgeClient::openFromTitle);
        try {
            ForgeConfig config = ForgeConfig.load(FMLPaths.CONFIGDIR.get());
            ForgeTranslationRuntime.initialize(config);
            UniversalTranslatorForgeMod.LOGGER.info(
                    "MC Auto Translation Tool initialized; enabled={}", config.enabled);
        } catch (Exception exception) {
            UniversalTranslatorForgeMod.LOGGER.error(
                    "MC Auto Translation Tool configuration failed; translation remains disabled",
                    exception);
            ForgeTranslationRuntime.shutdown();
        }
    }

    @Mod.EventBusSubscriber(
            modid = UniversalTranslatorForgeMod.MOD_ID,
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(RELOAD_SETTINGS);
            event.register(TOGGLE_TRANSLATION);
        }

        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(UniversalTranslatorForgeClient::loadConfig);
        }

    }

    @Mod.EventBusSubscriber(modid = UniversalTranslatorForgeMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {
        }

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            // START and END are both delivered for every client tick; without this guard the whole
            // body — beginFrame, the join hint countdown and the status notifier — ran twice per
            // tick, which made the "translation ready" hint appear after 1.5 s instead of 3 s. Every
            // other tree in this repo either checks the phase or subscribes to Post.
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            TranslationRenderContext.beginFrame();
            ForgeTranslationRuntime.refreshProtectedNames();
            Minecraft client = Minecraft.getInstance();
            boolean connected = client.level != null && client.getConnection() != null;
            if (connected && !connectedLastTick) {
                joinHintTicks = 60;
            } else if (!connected) {
                joinHintTicks = -1;
            }
            connectedLastTick = connected;
            if (connected && joinHintTicks > 0 && --joinHintTicks == 0) {
                client.gui.getChat().addMessage(
                        Component.translatable("message.universal_translator.join_hint"));
            }
            while (RELOAD_SETTINGS.consumeClick()) {
                openSettings(client);
            }
            while (TOGGLE_TRANSLATION.consumeClick()) {
                toggle(client);
            }
            ForgeRuntimeStatusNotifier.tick(client, connected);
        }

        @SubscribeEvent
        public static void outgoingChat(ClientChatEvent event) {
            String message = event.getMessage();
            if (resendingTranslatedMessage
                || !ForgeTranslationRuntime.shouldTranslateOutgoing(message)) {
                ForgeTranslationRuntime.protectOutgoingMessage(message);
                return;
            }
            event.setCanceled(true);
            Minecraft client = Minecraft.getInstance();
            client.gui.setOverlayMessage(
                    Component.translatable("message.universal_translator.outgoing_translating"),
                    false);
            ForgeTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                    client.execute(() -> sendCompletedMessage(client, message, result, error)));
        }
    }

    private static void openFromTitle(Object parent) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || parent instanceof UniversalTranslatorConfigScreen) {
            return;
        }
        ForgeConfig config;
        try {
            config = ForgeConfig.liveOrLoad(FMLPaths.CONFIGDIR.get());
        } catch (Exception exception) {
            // Still open the screen with whatever the runtime holds so the player can fix the
            // broken setting from the UI; a silent return here is what made the entry a dead key.
            config = ForgeTranslationRuntime.diagnosticsConfig();
            if (config == null) {
                // shutdown() drops the live copy on a failed load, so the snapshot this process did
                // manage to read is the only one left to edit; without it this whole branch is
                // unreachable, because liveOrLoad only throws when the live copy is already null.
                config = ForgeConfig.lastLoaded();
            }
            if (config == null) {
                UniversalTranslatorForgeMod.LOGGER.error(LogMessages.SETTINGS_OPEN_FAILED, exception);
                return;
            }
            UniversalTranslatorForgeMod.LOGGER.warn(
                    "Translation settings could not be re-read; opening the last loaded copy",
                    exception);
        }
        client.setScreen(new UniversalTranslatorConfigScreen((net.minecraft.client.gui.screens.Screen) parent, config));
    }

    private static void openSettings(Minecraft client) {
        try {
            ForgeConfig config = ForgeConfig.liveOrLoad(FMLPaths.CONFIGDIR.get());
            client.setScreen(new UniversalTranslatorConfigScreen(client.screen, config));
        } catch (Exception exception) {
            // Same fallback as the title-screen entry: a configuration that cannot be re-read is
            // the case this screen exists for, so it opens on the last snapshot this process had.
            ForgeConfig fallback = ForgeTranslationRuntime.diagnosticsConfig();
            if (fallback == null) {
                fallback = ForgeConfig.lastLoaded();
            }
            if (fallback != null) {
                UniversalTranslatorForgeMod.LOGGER.warn(
                        "Translation settings could not be re-read; opening the last loaded copy",
                        exception);
                client.setScreen(new UniversalTranslatorConfigScreen(client.screen, fallback));
                return;
            }
            UniversalTranslatorForgeMod.LOGGER.error(LogMessages.SETTINGS_OPEN_FAILED, exception);
            client.gui.setOverlayMessage(
                    Component.translatable("message.universal_translator.settings_open_failed"), false);
        }
    }

    private static void toggle(Minecraft client) {
        try {
            // The live configuration is what F8 acts on. Re-reading the file first parsed the whole
            // properties file on the client tick only to throw the result away.
            ForgeConfig previous = ForgeTranslationRuntime.diagnosticsConfig();
            if (previous == null) {
                previous = ForgeConfig.load(FMLPaths.CONFIGDIR.get());
            }
            ForgeConfig updated = previous.withEnabled(!previous.enabled);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            ForgeTranslationRuntime.initialize(updated);
            ForgeRuntimeStatusNotifier.reset();
            // Queued off the client tick: a save is a temporary create, a buffered write, an atomic
            // move and two ownership changes right before the next frame. A failed write is logged
            // rather than shown, because the toggle itself has already taken effect in memory.
            ForgeConfigWriter.saveOffTick(updated, failure ->
                    UniversalTranslatorForgeMod.LOGGER.warn(
                            "Could not persist the toggled settings", failure));
            client.gui.setOverlayMessage(
                    Component.translatable(
                            "message.universal_translator.toggle",
                            Component.translatable(updated.enabled
                                    ? "value.universal_translator.enabled"
                                    : "value.universal_translator.disabled")),
                    false);
        } catch (Exception exception) {
            UniversalTranslatorForgeMod.LOGGER.error(
                    "Could not toggle MC Auto Translation Tool", exception);
            client.gui.setOverlayMessage(
                    Component.translatable("message.universal_translator.toggle_failed"), false);
        }
    }

    private static void sendCompletedMessage(
            Minecraft client,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        if (client.getConnection() == null) {
            client.gui.getChat().addMessage(
                    Component.translatable("message.universal_translator.outgoing_disconnected"));
            return;
        }
        boolean failed = error != null || result == null || result.isFailure();
        String outgoing = failed || !result.isTranslated()
                ? original : result.getTranslatedText();
        boolean tooLong = outgoing.length() > 256;
        if (tooLong) {
            outgoing = original;
        }
        ForgeTranslationRuntime.protectOutgoingMessage(outgoing);
        resendingTranslatedMessage = true;
        try {
            client.getConnection().sendChat(outgoing);
        } finally {
            resendingTranslatedMessage = false;
        }
        if (failed) {
            client.gui.getChat().addMessage(
                    Component.translatable("message.universal_translator.outgoing_failed"));
        } else if (tooLong) {
            client.gui.getChat().addMessage(
                    Component.translatable("message.universal_translator.outgoing_too_long"));
        }
    }
}
