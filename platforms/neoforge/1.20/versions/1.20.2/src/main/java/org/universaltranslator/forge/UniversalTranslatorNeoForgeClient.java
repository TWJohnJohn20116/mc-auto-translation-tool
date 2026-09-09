package org.universaltranslator.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.event.TickEvent;
import org.lwjgl.glfw.GLFW;
import org.universaltranslator.core.TranslationResult;

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

    private static void loadConfig() {
        try {
            ForgeConfig config = ForgeConfig.load(FMLPaths.CONFIGDIR.get());
            ForgeTranslationRuntime.initialize(config);
            ForgeTranslationRuntime.setTitleSettingsOpener(UniversalTranslatorNeoForgeClient::openFromTitle);
            UniversalTranslatorNeoForgeMod.LOGGER.info(
                    "MC Auto Translation Tool initialized; enabled={}", config.enabled);
        } catch (Exception exception) {
            UniversalTranslatorNeoForgeMod.LOGGER.error(
                    "MC Auto Translation Tool configuration failed; translation remains disabled",
                    exception);
            ForgeTranslationRuntime.shutdown();
        }
    }

    @Mod.EventBusSubscriber(
            modid = UniversalTranslatorNeoForgeMod.MOD_ID,
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
            event.enqueueWork(UniversalTranslatorNeoForgeClient::loadConfig);
        }
    }

    @Mod.EventBusSubscriber(
            modid = UniversalTranslatorNeoForgeMod.MOD_ID,
            value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {
        }

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
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
            ForgeConfig config = ForgeConfig.load(FMLPaths.CONFIGDIR.get());
            client.setScreen(new UniversalTranslatorConfigScreen((net.minecraft.client.gui.screens.Screen) parent, config));
        } catch (Exception exception) {
            UniversalTranslatorNeoForgeMod.LOGGER.error("Could not open MC Auto Translation Tool settings", exception);
        }
    }

    private static void openSettings(Minecraft client) {
        try {
            ForgeConfig config = ForgeConfig.load(FMLPaths.CONFIGDIR.get());
            client.setScreen(new UniversalTranslatorConfigScreen(client.screen, config));
        } catch (Exception exception) {
            UniversalTranslatorNeoForgeMod.LOGGER.error("Could not open MC Auto Translation Tool settings", exception);
            client.gui.setOverlayMessage(
                    Component.translatable("message.universal_translator.settings_open_failed"), false);
        }
    }

    private static void toggle(Minecraft client) {
        try {
            ForgeConfig previous = ForgeConfig.load(FMLPaths.CONFIGDIR.get());
            ForgeConfig updated = previous.withEnabled(!previous.enabled);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            ForgeTranslationRuntime.initialize(updated);
            updated.save();
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
