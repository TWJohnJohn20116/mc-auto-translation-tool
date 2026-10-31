package org.universaltranslator.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.LogMessages;

/** NeoForge client lifecycle and input integration. */
public final class UniversalTranslatorNeoForgeClient {
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

    private UniversalTranslatorNeoForgeClient() {
    }

    /**
     * The configuration the runtime is running, or a fresh read before the first load.
     *
     * <p>Reading the file on every key press put a full {@code .properties} parse (plus a directory
     * check) on the tick thread; after startup the runtime already holds the same values.
     */
    private static ForgeConfig liveConfig() throws java.io.IOException {
        // One implementation for every Forge-family tree; see ForgeConfig.liveOrLoad.
        return ForgeConfig.liveOrLoad(FMLPaths.CONFIGDIR.get());
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
                                org.universaltranslator.core.ShutdownBudget.HOOK_JOIN_MILLIS);
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
        // session whenever the load threw, and it is the only entry that can repair a broken file.
        ForgeTranslationRuntime.setTitleSettingsOpener(UniversalTranslatorNeoForgeClient::openFromTitle);
        try {
            ForgeConfig config = ForgeConfig.load(FMLPaths.CONFIGDIR.get());
            ForgeTranslationRuntime.initialize(config);
            UniversalTranslatorNeoForgeMod.LOGGER.info(
                    "MC Auto Translation Tool initialized; enabled={}", config.enabled);
        } catch (Exception exception) {
            UniversalTranslatorNeoForgeMod.LOGGER.error(
                    "MC Auto Translation Tool configuration failed; translation remains disabled",
                    exception);
            ForgeTranslationRuntime.shutdown();
        }
    }

    @EventBusSubscriber(
            modid = UniversalTranslatorNeoForgeMod.MOD_ID,
            value = Dist.CLIENT,
            bus = EventBusSubscriber.Bus.MOD)
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
            event.enqueueWork(UniversalTranslatorNeoForgeClient::loadConfig);
        }
    }

    @EventBusSubscriber(modid = UniversalTranslatorNeoForgeMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {
        }

        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Post event) {
            // Same two lines as the Forge client's tick: the protected-name snapshot and the render
            // latches are advanced here, on the client thread, instead of from inside a draw call.
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
            Minecraft client = Minecraft.getInstance();
            event.setCanceled(true);
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
        try {
            ForgeConfig config = ForgeConfig.liveOrLoad(FMLPaths.CONFIGDIR.get());
            client.setScreen(new UniversalTranslatorConfigScreen((net.minecraft.client.gui.screens.Screen) parent, config));
        } catch (Exception exception) {
            UniversalTranslatorNeoForgeMod.LOGGER.error(LogMessages.SETTINGS_OPEN_FAILED, exception);
        }
    }

    private static void openSettings(Minecraft client) {
        try {
            ForgeConfig config = liveConfig();
            client.setScreen(new UniversalTranslatorConfigScreen(client.screen, config));
        } catch (Exception exception) {
            UniversalTranslatorNeoForgeMod.LOGGER.error(LogMessages.SETTINGS_OPEN_FAILED, exception);
            client.gui.setOverlayMessage(
                    Component.translatable("message.universal_translator.settings_open_failed"), false);
        }
    }

    private static void toggle(Minecraft client) {
        try {
            ForgeConfig previous = liveConfig();
            ForgeConfig updated = previous.withEnabled(!previous.enabled);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            ForgeTranslationRuntime.initialize(updated);
            ForgeRuntimeStatusNotifier.reset();
            // Queued on the shared daemon writer instead of a per-client executor.
            ForgeConfigWriter.saveOffTick(updated, failure -> {
                UniversalTranslatorNeoForgeMod.LOGGER.error(
                        "Could not save MC Auto Translation Tool settings", failure);
                client.execute(() -> client.gui.setOverlayMessage(
                        Component.translatable("message.universal_translator.toggle_failed"), false));
            });
            client.gui.setOverlayMessage(
                    Component.translatable(
                            "message.universal_translator.toggle",
                            Component.translatable(updated.enabled
                                    ? "value.universal_translator.enabled"
                                    : "value.universal_translator.disabled")),
                    false);
        } catch (Exception exception) {
            UniversalTranslatorNeoForgeMod.LOGGER.error(
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