package org.universaltranslator.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.LogMessages;

/** Forge client lifecycle and input integration. */
public final class UniversalTranslatorForgeClient {
    private static final KeyMapping RELOAD_SETTINGS = new KeyMapping(
            "key.universal_translator.open_settings",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_U,
            "key.categories.misc");
    private static final KeyMapping TOGGLE_TRANSLATION = new KeyMapping(
            "key.universal_translator.toggle_translation",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_F8,
            "key.categories.misc");
    private static boolean connectedLastTick;
    private static int joinHintTicks = -1;
    private static boolean resendingTranslatedMessage;

    private UniversalTranslatorForgeClient() {
    }

    private static void loadConfig() {
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
        public static void clientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                ClientRegistry.registerKeyBinding(RELOAD_SETTINGS);
                ClientRegistry.registerKeyBinding(TOGGLE_TRANSLATION);
                loadConfig();
            });
        }

    }

    @Mod.EventBusSubscriber(modid = UniversalTranslatorForgeMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {
        }

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            TranslationRenderContext.beginFrame();
            // Starts the render memo window: one tick of staleness at most, and a replaced or
            // shut-down configuration can never leak into a memoised line.
            ForgeTranslationRuntime.beginRenderFrame();
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
                        new TranslatableComponent("message.universal_translator.join_hint"));
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
                    new TranslatableComponent("message.universal_translator.outgoing_translating"),
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
            UniversalTranslatorForgeMod.LOGGER.error(
                    LogMessages.SETTINGS_OPEN_FAILED, exception);
            client.gui.setOverlayMessage(
                    new TranslatableComponent("message.universal_translator.settings_open_failed"), false);
        }
    }

    private static void toggle(Minecraft client) {
        try {
            // The live configuration is what F8 acts on; re-reading the file parsed the whole
            // properties file on the client tick only to throw the result away.
            ForgeConfig previous = ForgeConfig.liveOrLoad(FMLPaths.CONFIGDIR.get());
            ForgeConfig updated = previous.withEnabled(!previous.enabled);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            ForgeTranslationRuntime.initialize(updated);
            ForgeRuntimeStatusNotifier.reset();
            // Queued off the client tick; a failed write is logged rather than shown, because the
            // toggle itself has already taken effect in memory.
            ForgeConfigWriter.saveOffTick(updated, failure ->
                    UniversalTranslatorForgeMod.LOGGER.warn(
                            "Could not persist the toggled settings", failure));
            client.gui.setOverlayMessage(
                    new TranslatableComponent(
                            "message.universal_translator.toggle",
                            new TranslatableComponent(updated.enabled
                                    ? "value.universal_translator.enabled"
                                    : "value.universal_translator.disabled")),
                    false);
        } catch (Exception exception) {
            UniversalTranslatorForgeMod.LOGGER.error(
                    "Could not toggle MC Auto Translation Tool", exception);
            client.gui.setOverlayMessage(
                    new TranslatableComponent("message.universal_translator.toggle_failed"), false);
        }
    }

    private static void sendCompletedMessage(
            Minecraft client,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        // Both checks, and the same failure message as the other three targets: a connection that is
        // still open while the local player is gone used to skip the send and stay silent.
        if (client.getConnection() == null || client.player == null) {
            client.gui.getChat().addMessage(
                    new TranslatableComponent("message.universal_translator.outgoing_disconnected"));
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
            client.player.chat(outgoing);
        } finally {
            resendingTranslatedMessage = false;
        }
        if (failed) {
            client.gui.getChat().addMessage(
                    new TranslatableComponent("message.universal_translator.outgoing_failed"));
        } else if (tooLong) {
            client.gui.getChat().addMessage(
                    new TranslatableComponent("message.universal_translator.outgoing_too_long"));
        }
    }
}
