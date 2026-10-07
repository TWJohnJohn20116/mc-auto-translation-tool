package org.universaltranslator.forge.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.TranslationActivity;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationStatusLocalizer;

import java.io.File;

/** Forge 1.8.9/1.12.2 compatible key binding and settings-screen launcher. */
public final class LegacyClientEvents {
    private static final int HOME_OPEN = 31_700;
    private static final long FAILURE_NOTIFICATION_COOLDOWN_MILLIS = 60_000L;
    private static final LegacyClientEvents INSTANCE = new LegacyClientEvents();
    private static final KeyBinding OPEN_SETTINGS = new KeyBinding(
            "key.universal_translator.open_settings", Keyboard.KEY_U, "MC Auto Translation Tool");
    private static final KeyBinding TOGGLE_TRANSLATION = new KeyBinding(
            "key.universal_translator.toggle_translation", Keyboard.KEY_F8, "MC Auto Translation Tool");
    private static File configDirectory;
    private static boolean registered;
    private boolean connectedLastTick;
    private int joinHintTicks = -1;
    private String lastRuntimeStatus = "";
    private long nextFailureNotificationAt;

    private LegacyClientEvents() {
    }

    static synchronized void initialize(File directory) {
        configDirectory = directory;
        if (!registered) {
            ClientRegistry.registerKeyBinding(OPEN_SETTINGS);
            ClientRegistry.registerKeyBinding(TOGGLE_TRANSLATION);
            MinecraftForge.EVENT_BUS.register(INSTANCE);
            registered = true;
        }
    }

    @SubscribeEvent
    public void onTitleScreenInitialized(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(LegacyVersionAccess.eventScreen(event) instanceof GuiMainMenu)) {
            return;
        }
        int x = Math.max(4, LegacyVersionAccess.eventScreen(event).width - 136);
        LegacyVersionAccess.buttonList(event).add(new GuiButton(
                HOME_OPEN, x, 6, 132, 20, tr("screen.universal_translator.home.open")));
    }

    @SubscribeEvent
    public void onTitleScreenButton(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (!(LegacyVersionAccess.eventScreen(event) instanceof GuiMainMenu)) {
            return;
        }
        if (LegacyVersionAccess.actionButton(event).id != HOME_OPEN) {
            return;
        }
        event.setCanceled(true);
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen instanceof LegacyConfigScreen || configDirectory == null) {
            return;
        }
        try {
            LegacyConfig config = LegacyConfig.load(configDirectory);
            minecraft.displayGuiScreen(new LegacyConfigScreen(
                    LegacyVersionAccess.eventScreen(event), config));
        } catch (Exception exception) {
            System.err.println("[MC Auto Translation Tool] Could not open settings: " + exception);
        }
    }

    @SubscribeEvent
    public void onChatKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!Keyboard.getEventKeyState()
                || (Keyboard.getEventKey() != Keyboard.KEY_RETURN
                && Keyboard.getEventKey() != Keyboard.KEY_NUMPADENTER)) {
            return;
        }
        String message = LegacyLocalTextGuard.currentChatInput(Minecraft.getMinecraft().currentScreen);
        if (message.isEmpty()) {
            return;
        }
        if (!LegacyTranslationRuntime.shouldTranslateOutgoing(message)) {
            LegacyTranslationRuntime.protectOutgoingMessage(message);
            return;
        }
        event.setCanceled(true);
        Minecraft minecraft = Minecraft.getMinecraft();
        LegacyVersionAccess.rememberSentMessage(minecraft, message);
        minecraft.displayGuiScreen(null);
        minecraft.setIngameFocus();
        minecraft.ingameGUI.setRecordPlayingMessage(
                tr("message.universal_translator.outgoing_translating"));
        LegacyTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                minecraft.addScheduledTask(() -> sendCompletedMessage(
                        minecraft, message, result, error)));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        boolean connected = LegacyVersionAccess.connection(minecraft) != null;
        if (connected && !connectedLastTick) {
            joinHintTicks = 60;
        } else if (!connected) {
            joinHintTicks = -1;
        }
        connectedLastTick = connected;
        if (connected && joinHintTicks > 0 && --joinHintTicks == 0) {
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    tr("message.universal_translator.join_hint"));
            long maximumMemoryMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            if (maximumMemoryMiB < 768L) {
                LegacyVersionAccess.showLocalChatMessage(minecraft,
                        tr("message.universal_translator.memory_warning", maximumMemoryMiB));
            }
        }
        if (TOGGLE_TRANSLATION.isPressed() && configDirectory != null) {
            LegacyConfig previous = null;
            boolean runtimeChanged = false;
            try {
                previous = LegacyConfig.load(configDirectory);
                LegacyConfig updated = previous.withEnabled(!previous.enabled);
                if (updated.enabled) {
                    updated.validateProviderConfiguration();
                }
                runtimeChanged = true;
                LegacyTranslationRuntime.initialize(updated);
                lastRuntimeStatus = "";
                nextFailureNotificationAt = 0L;
                updated.save();
                minecraft.ingameGUI.setRecordPlayingMessage(
                        tr("message.universal_translator.toggle", tr(updated.enabled
                                ? "value.universal_translator.enabled"
                                : "value.universal_translator.disabled")));
            } catch (Exception exception) {
                if (runtimeChanged && previous != null) {
                    try {
                        LegacyTranslationRuntime.initialize(previous);
                    } catch (Exception restoreFailure) {
                        exception.addSuppressed(restoreFailure);
                    }
                }
                System.err.println("[MC Auto Translation Tool] Could not toggle translation: " + exception);
            }
        }
        notifyRuntimeStatus(minecraft, connected);
        if (!OPEN_SETTINGS.isPressed()) {
            return;
        }
        if (minecraft.currentScreen instanceof LegacyConfigScreen || configDirectory == null) {
            return;
        }
        try {
            LegacyConfig config = LegacyConfig.load(configDirectory);
            minecraft.displayGuiScreen(new LegacyConfigScreen(minecraft.currentScreen, config));
        } catch (Exception exception) {
            System.err.println("[MC Auto Translation Tool] Could not open settings: " + exception);
        }
    }

    private void notifyRuntimeStatus(Minecraft minecraft, boolean connected) {
        String current = connected ? LegacyTranslationRuntime.status() : "";
        if (current == null) {
            current = "";
        }
        if (current.equals(lastRuntimeStatus)) {
            return;
        }
        lastRuntimeStatus = current;
        if (current.isEmpty()) {
            nextFailureNotificationAt = 0L;
            return;
        }
        String localized = TranslationStatusLocalizer.localize(current, LegacyClientEvents::tr);
        if (isFailureStatus(current)) {
            long now = System.currentTimeMillis();
            if (now < nextFailureNotificationAt) {
                return;
            }
            nextFailureNotificationAt = now + FAILURE_NOTIFICATION_COOLDOWN_MILLIS;
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    tr("message.universal_translator.runtime_failed", localized));
        } else {
            nextFailureNotificationAt = 0L;
            minecraft.ingameGUI.setRecordPlayingMessage(
                    tr("message.universal_translator.runtime_status", localized));
        }
    }

    private static boolean isFailureStatus(String status) {
        return TranslationStatusLocalizer.isFailure(status);
    }

    private static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }

    /**
     * Paints the translation status indicator on the HUD.
     *
     * <p>Unlike the Fabric families this needs no bytecode hook: Forge fires
     * {@code RenderGameOverlayEvent.Post} after the vanilla HUD is drawn, which is exactly where the
     * square belongs. Everything drawn comes from {@code HudIndicatorSettings}: the corner, size,
     * margin, colour, what appears inside the square, and when it is drawn at all.
     */
    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        // Read the settings first: a disabled indicator draws nothing at all.
        HudIndicatorSettings settings = LegacyTranslationRuntime.homeSettings().getHudIndicator();
        if (!settings.isIndicator()) {
            return;
        }
        switch (settings.getVisibility()) {
            case WHILE_TRANSLATING:
                if (!TranslationActivity.isTranslating()) {
                    return;
                }
                break;
            case WHEN_DISABLED:
                // The translation master switch, deliberately not the indicator's own option: the
                // indicator option already gated every value of this enum above.
                if (LegacyTranslationRuntime.homeSettings().isEnabled()) {
                    return;
                }
                break;
            case ALWAYS:
            default:
                break;
        }
        Minecraft client = Minecraft.getMinecraft();
        if (client == null) {
            return;
        }
        ScaledResolution resolution = new ScaledResolution(client);
        int windowWidth = resolution.getScaledWidth();
        int windowHeight = resolution.getScaledHeight();
        HudIndicatorCorner corner = settings.getCorner();
        int margin = settings.getMargin();
        int size = settings.getSize();
        int left = corner.isRight() ? windowWidth - margin - size : margin;
        int top = corner.isBottom() ? windowHeight - margin - size : margin;
        // The drag offset comes from the settings screen, but the config file is plain text a
        // player can edit, so clamp the final position instead of trusting the stored range: the
        // indicator must never end up somewhere it cannot be dragged back from.
        left += settings.getOffsetX();
        top += settings.getOffsetY();
        left = Math.max(0, Math.min(windowWidth - size, left));
        top = Math.max(0, Math.min(windowHeight - size, top));
        int right = left + size;
        int bottom = top + size;
        int borderColor = 0xFF000000;
        // One pixel of opaque black keeps the indicator readable against sky and bright terrain.
        Gui.drawRect(left - 1, top - 1, right + 1, top, borderColor);
        Gui.drawRect(left - 1, bottom, right + 1, bottom + 1, borderColor);
        Gui.drawRect(left - 1, top, left, bottom, borderColor);
        Gui.drawRect(right, top, right + 1, bottom, borderColor);
        if (LegacyTranslationRuntime.homeSettings().isEnabled()) {
            // Solid colour while translation is on: configurable, green by default.
            Gui.drawRect(left, top, right, bottom, settings.getColor().argb());
        } else {
            // A hollow red frame while translation is off, so the two states differ in shape as well
            // as in colour and remain distinguishable for players who cannot separate the two hues.
            int disabledColor = 0xFFFF5555;
            Gui.drawRect(left, top, right, top + 1, disabledColor);
            Gui.drawRect(left, bottom - 1, right, bottom, disabledColor);
            Gui.drawRect(left, top + 1, left + 1, bottom - 1, disabledColor);
            Gui.drawRect(right - 1, top + 1, right, bottom - 1, disabledColor);
        }
        // The label is drawn in both states: the target language and the provider are settings, so
        // they stay informative even while translation is off.
        HudIndicatorContent content = settings.getContent();
        if (content == HudIndicatorContent.DOT) {
            return;
        }
        String label = null;
        if (content == HudIndicatorContent.LANGUAGE) {
            label = LegacyTranslationRuntime.homeSettings().getTargetLanguage();
        } else if (content == HudIndicatorContent.PROVIDER) {
            label = LegacyTranslationRuntime.homeSettings().getProvider();
        } else if (content == HudIndicatorContent.ACTIVITY && TranslationActivity.isTranslating()) {
            label = I18n.format("value.universal_translator.hud_content.activity");
        }
        if (label == null || label.isEmpty()) {
            return;
        }
        FontRenderer font = LegacyVersionAccess.fontRenderer();
        // The legacy FontRenderer is hooked so that everything it renders can be translated;
        // measuring or drawing the label unguarded would feed the indicator's own text back into
        // the translation path.
        LegacyRenderContext.pushTextInput();
        try {
            int labelWidth = font.getStringWidth(label);
            // Beside the square, never over it: a 6px square cannot hold "zh-TW", and a label
            // centred on a right-corner square would run off the screen edge.
            int labelLeft = corner.isRight() ? left - 2 - labelWidth : right + 2;
            // y is the top of the text line, so centre it against the square by hand.
            int labelY = top + (size - 8) / 2;
            font.drawStringWithShadow(label, (float) labelLeft, (float) labelY, 0xFFFFFFFF);
        } finally {
            LegacyRenderContext.popTextInput();
        }
    }

    private static void sendCompletedMessage(
            Minecraft minecraft,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        if (LegacyVersionAccess.connection(minecraft) == null) {
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    tr("message.universal_translator.outgoing_disconnected"));
            return;
        }
        boolean failed = error != null || result == null || result.isFailure();
        String outgoing = failed || !result.isTranslated()
                ? original : result.getTranslatedText();
        boolean tooLong = outgoing.length() > LegacyVersionAccess.maximumChatLength();
        if (tooLong) {
            outgoing = original;
        }
        LegacyTranslationRuntime.protectOutgoingMessage(outgoing);
        LegacyVersionAccess.sendChatMessage(minecraft, outgoing);
        if (failed) {
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    tr("message.universal_translator.outgoing_failed"));
        } else if (tooLong) {
            LegacyVersionAccess.showLocalChatMessage(minecraft,
                    tr("message.universal_translator.outgoing_too_long"));
        }
    }
}

