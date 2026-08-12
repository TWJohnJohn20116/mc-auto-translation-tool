package org.universaltranslator.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.universaltranslator.core.TranslationResult;

public final class UniversalTranslatorForgeClient {
    private static final KeyBinding RELOAD_SETTINGS = new KeyBinding(
            "key.universal_translator.open_settings", InputMappings.Type.KEYSYM,
            85, "key.categories.misc");
    private static final KeyBinding TOGGLE_TRANSLATION = new KeyBinding(
            "key.universal_translator.toggle_translation", InputMappings.Type.KEYSYM,
            297, "key.categories.misc");
    private static boolean connectedLastTick;
    private static int joinHintTicks = -1;

    private UniversalTranslatorForgeClient() {
    }

    private static void loadConfig() {
        try {
            ForgeConfig config = ForgeConfig.load(FMLPaths.CONFIGDIR.get());
            ForgeTranslationRuntime.initialize(config);
            UniversalTranslatorForgeMod.LOGGER.info(
                    "MC Auto Translation Tool initialized; enabled={}", config.enabled);
        } catch (Exception exception) {
            UniversalTranslatorForgeMod.LOGGER.error("Translation initialization failed", exception);
            ForgeTranslationRuntime.shutdown();
        }
    }

    @Mod.EventBusSubscriber(modid = UniversalTranslatorForgeMod.MOD_ID,
            value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
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
                        new TranslationTextComponent("message.universal_translator.join_hint"));
            }
            while (RELOAD_SETTINGS.consumeClick()) {
                loadConfig();
                client.gui.getChat().addMessage(new TranslationTextComponent(
                        "message.universal_translator.settings_reloaded",
                        FMLPaths.CONFIGDIR.get().resolve("universal-translator.properties")));
            }
            while (TOGGLE_TRANSLATION.consumeClick()) {
                toggle(client);
            }
        }

        @SubscribeEvent
        public static void outgoingChat(ClientChatEvent event) {
            String message = event.getMessage();
            if (!ForgeTranslationRuntime.shouldTranslateOutgoing(message)) {
                ForgeTranslationRuntime.protectOutgoingMessage(message);
                return;
            }
            event.setCanceled(true);
            Minecraft client = Minecraft.getInstance();
            client.gui.setOverlayMessage(new TranslationTextComponent(
                    "message.universal_translator.outgoing_translating"), false);
            ForgeTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                    client.execute(() -> sendCompletedMessage(client, message, result, error)));
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
            client.gui.setOverlayMessage(new TranslationTextComponent(
                    "message.universal_translator.toggle", new TranslationTextComponent(
                    updated.enabled ? "value.universal_translator.enabled"
                            : "value.universal_translator.disabled")), false);
        } catch (Exception exception) {
            UniversalTranslatorForgeMod.LOGGER.error("Could not toggle translation", exception);
            client.gui.setOverlayMessage(new TranslationTextComponent(
                    "message.universal_translator.toggle_failed"), false);
        }
    }

    private static void sendCompletedMessage(Minecraft client, String original,
            TranslationResult result, Throwable error) {
        if (client.player == null) {
            return;
        }
        boolean failed = error != null || result == null || result.isFailure();
        String outgoing = failed || !result.isTranslated() ? original : result.getTranslatedText();
        if (outgoing.length() > 256) {
            outgoing = original;
        }
        ForgeTranslationRuntime.protectOutgoingMessage(outgoing);
        client.player.chat(outgoing);
        if (failed) {
            client.gui.getChat().addMessage(new TranslationTextComponent(
                    "message.universal_translator.outgoing_failed"));
        }
    }
}
