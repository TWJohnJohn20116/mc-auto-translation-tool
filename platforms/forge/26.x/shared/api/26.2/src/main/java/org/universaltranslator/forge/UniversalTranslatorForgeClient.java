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

/** Forge client lifecycle and input integration. */
public final class UniversalTranslatorForgeClient {
    private static final KeyMapping RELOAD_SETTINGS = new KeyMapping(
            "key.universal_translator.open_settings",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U,
            KeyMapping.Category.MISC);
    private static final KeyMapping TOGGLE_TRANSLATION = new KeyMapping(
            "key.universal_translator.toggle_translation",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            KeyMapping.Category.MISC);
    private static boolean connectedLastTick;
    private static int joinHintTicks = -1;

    private UniversalTranslatorForgeClient() {
    }

    static void registerDirectEvents() {
        RegisterKeyMappingsEvent.BUS.addListener(UniversalTranslatorForgeClient::registerKeys);
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(RELOAD_SETTINGS);
        event.register(TOGGLE_TRANSLATION);
    }

    private static void loadConfig() {
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
            event.enqueueWork(UniversalTranslatorForgeClient::loadConfig);
        }

    }

    @Mod.EventBusSubscriber(modid = UniversalTranslatorForgeMod.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {
        }

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent.Post event) {
            Minecraft client = Minecraft.getInstance();
            boolean connected = client.level != null && client.getConnection() != null;
            if (connected && !connectedLastTick) {
                joinHintTicks = 60;
            } else if (!connected) {
                joinHintTicks = -1;
            }
            connectedLastTick = connected;
            if (connected && joinHintTicks > 0 && --joinHintTicks == 0) {
                client.gui.hud.getChat().addClientSystemMessage(
                        Component.translatable("message.universal_translator.join_hint"));
            }
            while (RELOAD_SETTINGS.consumeClick()) {
                loadConfig();
                client.gui.hud.getChat().addClientSystemMessage(Component.translatable(
                        "message.universal_translator.settings_reloaded",
                        FMLPaths.CONFIGDIR.get().resolve("universal-translator.properties")));
            }
            while (TOGGLE_TRANSLATION.consumeClick()) {
                toggle(client);
            }
        }

        @SubscribeEvent
        public static boolean outgoingChat(ClientChatEvent event) {
            String message = event.getMessage();
            if (!ForgeTranslationRuntime.shouldTranslateOutgoing(message)) {
                ForgeTranslationRuntime.protectOutgoingMessage(message);
                return false;
            }
            Minecraft client = Minecraft.getInstance();
            client.gui.hud.setOverlayMessage(
                    Component.translatable("message.universal_translator.outgoing_translating"),
                    false);
            ForgeTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                    client.execute(() -> sendCompletedMessage(client, message, result, error)));
            return true;
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
            client.gui.hud.setOverlayMessage(
                    Component.translatable(
                            "message.universal_translator.toggle",
                            Component.translatable(updated.enabled
                                    ? "value.universal_translator.enabled"
                                    : "value.universal_translator.disabled")),
                    false);
        } catch (Exception exception) {
            UniversalTranslatorForgeMod.LOGGER.error(
                    "Could not toggle MC Auto Translation Tool", exception);
            client.gui.hud.setOverlayMessage(
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
            client.gui.hud.getChat().addClientSystemMessage(
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
        client.getConnection().sendChat(outgoing);
        if (failed) {
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.translatable("message.universal_translator.outgoing_failed"));
        } else if (tooLong) {
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.translatable("message.universal_translator.outgoing_too_long"));
        }
    }
}
