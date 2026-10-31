package org.universaltranslator.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
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

    /**
     * Gives the queued configuration writes a bounded moment to land when the process ends.
     *
     * <p>Only a failed startup ever called {@code ForgeTranslationRuntime.shutdown()}, so the writer's
     * own flush never ran: "press F8 and quit" dropped the toggle, and the session and the screen
     * worker threads outlived the configuration they belonged to. A shutdown hook is the one seam
     * every supported loader and game version has without naming a version-specific stopping event.
     * The protocol itself lives in {@link ForgeConfigWriter}, so the trees that share it cannot drift
     * apart, and this class only names the action to run when the process ends.
     */
    private static void installConfigFlushHook() {
        ForgeConfigWriter.installShutdownHook(new Runnable() {
            @Override
            public void run() {
                ForgeTranslationRuntime.shutdown();
            }
        });
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
        public static void clientTick(TickEvent.ClientTickEvent.Post event) {
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
        public static boolean outgoingChat(ClientChatEvent event) {
            // Returning true from a listener on this event bus cancels the event, which is why this
            // overload returns boolean instead of calling setCanceled like 1.21-1.21.5 does. The
            // resend guard below is what keeps the translated line from being sent a second time.
            String message = event.getMessage();
            if (resendingTranslatedMessage
                || !ForgeTranslationRuntime.shouldTranslateOutgoing(message)) {
                ForgeTranslationRuntime.protectOutgoingMessage(message);
                return false;
            }
            Minecraft client = Minecraft.getInstance();
            client.gui.setOverlayMessage(
                    Component.translatable("message.universal_translator.outgoing_translating"),
                    false);
            ForgeTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                    client.execute(() -> sendCompletedMessage(client, message, result, error)));
            return true;
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
            // shutdown() drops the live copy on a failed load, so the snapshot this process did
            // manage to read is the only one left to edit.
            config = ForgeConfig.forEditingOrNull(FMLPaths.CONFIGDIR.get());
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
            ForgeConfig fallback = ForgeConfig.forEditingOrNull(FMLPaths.CONFIGDIR.get());
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
