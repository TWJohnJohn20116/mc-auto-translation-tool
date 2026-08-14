package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import java.io.IOException;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationProviderCatalog;

/** Dependency-free settings UI shared by Forge 1.8.9 and 1.12.2. */
final class LegacyConfigScreen extends GuiScreen {
    private static final int ENABLED = 1;
    private static final int CACHE = 2;
    private static final int CHAT = 3;
    private static final int OTHER = 4;
    private static final int SAVE = 5;
    private static final int CANCEL = 6;
    private static final int PROVIDER = 7;
    private static final int DISPLAY = 8;
    private static final int DOWNLOAD = 9;
    private static final int FALLBACK = 10;
    private static final int MIXED_TEXT = 11;
    private static final int COLOR = 12;
    private static final int OUTGOING = 13;
    private static final int MODEL = 14;
    private static final int DIAGNOSTICS = 15;
    private static final int TARGET_LANGUAGE = 16;
    private static final int VANILLA = 17;
    private static final int OUTGOING_TARGET_LANGUAGE = 18;

    private final GuiScreen parent;
    private final LegacyConfig original;
    private boolean enabled;
    private boolean translateChat;
    private boolean translateOther;
    private boolean translateVanilla;
    private boolean translateOutgoing;
    private boolean diskCache;
    private boolean offlineAutoDownload;
    private OfflineModel offlineModel;
    private boolean apiFallback;
    private TranslationDisplayMode displayMode;
    private boolean translateEnglishOnly;
    private TranslationTextColor translatedTextColor;
    private String provider;
    private String llmEndpoint;
    private String llmApiKey;
    private String llmModel;
    private String targetLanguage;
    private String outgoingTargetLanguage;
    private GuiTextField endpoint;
    private FontRenderer renderer;
    private String status = "";

    LegacyConfigScreen(GuiScreen parent, LegacyConfig config) {
        this.parent = parent;
        this.original = config;
        this.enabled = config.enabled;
        this.translateChat = config.translateChat;
        this.translateOther = config.translateOther;
        this.translateVanilla = config.translateVanilla;
        this.translateOutgoing = config.translateOutgoing;
        this.diskCache = config.diskCache;
        this.offlineAutoDownload = config.offlineAutoDownload;
        this.offlineModel = config.offlineModel;
        this.apiFallback = config.apiFallback;
        this.displayMode = config.displayMode;
        this.translateEnglishOnly = config.translateEnglishOnly;
        this.translatedTextColor = config.translatedTextColor;
        this.provider = config.provider;
        this.llmEndpoint = config.llmEndpoint;
        this.llmApiKey = config.llmApiKey;
        this.llmModel = config.llmModel;
    }

    @Override
    public void initGui() {
        if (targetLanguage == null) {
            targetLanguage = TargetLanguage.canonicalize(original.targetLanguage);
        }
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getText();
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }
        buttonList.clear();
        renderer = LegacyVersionAccess.fontRenderer();
        Layout layout = layout();
        int left = layout.left;
        buttonList.add(new GuiButton(ENABLED, left, layout.row(0), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(CACHE, layout.right, layout.row(0), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(CHAT, left, layout.row(1), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(OTHER, layout.right, layout.row(1), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(PROVIDER, left, layout.row(2), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(DISPLAY, layout.right, layout.row(2), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(MIXED_TEXT, left, layout.row(3), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(COLOR, layout.right, layout.row(3), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(DOWNLOAD, left, layout.row(4), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(FALLBACK, layout.right, layout.row(4), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(MODEL, left, layout.row(5), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(VANILLA, left, layout.row(6), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(DIAGNOSTICS, layout.right, layout.row(6),
                layout.buttonWidth, 20, tr("screen.universal_translator.diagnostics.title")));
        buttonList.add(new GuiButton(TARGET_LANGUAGE, left, layout.targetY,
                layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(OUTGOING, layout.right, layout.targetY,
                layout.buttonWidth, 20, ""));
        endpoint = new GuiTextField(21, renderer, left, layout.endpointY, layout.buttonWidth, 20);
        endpoint.setMaxStringLength(512);
        endpoint.setText(endpointValue);
        buttonList.add(new GuiButton(OUTGOING_TARGET_LANGUAGE, layout.right, layout.endpointY,
                layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(SAVE, left, layout.saveY, layout.buttonWidth, 20,
                tr("screen.universal_translator.save")));
        buttonList.add(new GuiButton(CANCEL, layout.right, layout.saveY, layout.buttonWidth, 20,
                tr("gui.cancel")));
        refreshLabels();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ENABLED) {
            enabled = !enabled;
        } else if (button.id == CACHE) {
            diskCache = !diskCache;
        } else if (button.id == CHAT) {
            translateChat = !translateChat;
        } else if (button.id == OTHER) {
            translateOther = !translateOther;
        } else if (button.id == VANILLA) {
            translateVanilla = !translateVanilla;
        } else if (button.id == PROVIDER) {
            provider = nextProvider(provider);
        } else if (button.id == DISPLAY) {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
        } else if (button.id == MIXED_TEXT) {
            translateEnglishOnly = !translateEnglishOnly;
        } else if (button.id == COLOR) {
            translatedTextColor = translatedTextColor.next();
        } else if (button.id == DOWNLOAD) {
            if (isLlm()) {
                mc.displayGuiScreen(new LegacyLlmConfigScreen(
                        this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
            } else {
                offlineAutoDownload = !offlineAutoDownload;
            }
        } else if (button.id == FALLBACK) {
            apiFallback = !apiFallback;
        } else if (button.id == OUTGOING) {
            translateOutgoing = !translateOutgoing;
        } else if (button.id == MODEL) {
            offlineModel = offlineModel.next();
        } else if (button.id == DIAGNOSTICS) {
            mc.displayGuiScreen(new LegacyDiagnosticsScreen(this));
            return;
        } else if (button.id == TARGET_LANGUAGE) {
            targetLanguage = TargetLanguage.nextPreset(targetLanguage);
        } else if (button.id == OUTGOING_TARGET_LANGUAGE) {
            outgoingTargetLanguage = TargetLanguage.nextPreset(outgoingTargetLanguage);
        } else if (button.id == SAVE) {
            saveAndApply();
        } else if (button.id == CANCEL) {
            mc.displayGuiScreen(parent);
        }
        refreshLabels();
    }

    private void refreshLabels() {
        button(ENABLED).displayString = tr("screen.universal_translator.option.automatic", onOff(enabled));
        button(CHAT).displayString = tr("screen.universal_translator.option.chat", onOff(translateChat));
        button(OTHER).displayString = tr("screen.universal_translator.option.other", onOff(translateOther));
        button(VANILLA).displayString = tr("screen.universal_translator.option.vanilla", onOff(translateVanilla));
        button(CACHE).displayString = tr("screen.universal_translator.option.cache", onOff(diskCache));
        button(PROVIDER).displayString = tr("screen.universal_translator.option.provider", providerLabel());
        button(DISPLAY).displayString = tr("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated"));
        button(MIXED_TEXT).displayString = tr("screen.universal_translator.option.mixed", onOff(translateEnglishOnly));
        button(COLOR).displayString = tr("screen.universal_translator.option.color", colorLabel(translatedTextColor));
        button(DOWNLOAD).displayString = isLlm()
                ? tr("screen.universal_translator.option.llm_settings")
                : tr("screen.universal_translator.option.download", onOff(offlineAutoDownload));
        button(MODEL).displayString = tr("screen.universal_translator.option.model", offlineModel.displayName());
        button(FALLBACK).displayString = tr("screen.universal_translator.option.fallback", onOff(apiFallback));
        button(OUTGOING).displayString = tr("screen.universal_translator.option.outgoing", onOff(translateOutgoing));
        button(TARGET_LANGUAGE).displayString = tr("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage));
        button(OUTGOING_TARGET_LANGUAGE).displayString = tr(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage));
        button(DOWNLOAD).enabled = isOffline() || isLlm();
        button(MODEL).enabled = isOffline();
        button(FALLBACK).enabled = isOffline();
    }

    private GuiButton button(int id) {
        for (GuiButton button : buttonList) {
            if (button.id == id) {
                return button;
            }
        }
        throw new IllegalStateException("Missing button " + id);
    }

    private static String onOff(boolean value) {
        return tr(value ? "value.universal_translator.on" : "value.universal_translator.off");
    }

    private static boolean isFailureStatus(String value) {
        return TranslationStatusLocalizer.isFailure(value);
    }

    private void saveAndApply() {
        boolean runtimeChanged = false;
        try {
            LegacyConfig updated = original.withSettings(
                    enabled,
                    translateChat,
                    translateOther,
                    translateVanilla,
                    translateOutgoing,
                    targetLanguage,
                    outgoingTargetLanguage,
                    displayMode,
                    translateEnglishOnly,
                    translatedTextColor,
                    provider,
                    endpoint.getText(),
                    llmEndpoint,
                    llmApiKey,
                    llmModel,
                    offlineAutoDownload,
                    offlineModel,
                    apiFallback,
                    diskCache);
            if (updated.enabled && "tencent-hunyuan".equalsIgnoreCase(updated.provider)
                    && (updated.tencentSecretId.isEmpty() || updated.tencentSecretKey.isEmpty())) {
                throw new IllegalArgumentException(tr("error.universal_translator.tencent_credentials"));
            }
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            LegacyTranslationRuntime.initialize(updated);
            updated.save();
            mc.displayGuiScreen(parent);
        } catch (Exception exception) {
            if (runtimeChanged) {
                try {
                    LegacyTranslationRuntime.initialize(original);
                } catch (Exception restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            status = tr("status.universal_translator.save_failed", exception.getMessage());
        }
    }

    @Override
    public void updateScreen() {
        endpoint.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (endpoint.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        endpoint.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(renderer, tr("screen.universal_translator.settings.title"),
                width / 2, 18, 0xFFFFFF);
        Layout layout = layout();
        int left = layout.left;
        drawString(renderer, tr("screen.universal_translator.target_language_hint"),
                left, layout.targetY - 11, 0xA0A0A0);
        drawString(renderer, tr("screen.universal_translator.endpoint_hint"),
                left, layout.endpointY - 11, 0xA0A0A0);
        drawString(renderer, tr("screen.universal_translator.outgoing_target_hint"),
                layout.right, layout.endpointY - 11, 0xA0A0A0);
        endpoint.drawTextBox();
        String rawRuntimeStatus = LegacyTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                LegacyConfigScreen::tr);
        int belowSave = layout.saveY + 28;
        int messageY = belowSave <= height - 10 ? belowSave : layout.saveY - 14;
        if (!status.isEmpty()) {
            drawCenteredString(renderer, status, width / 2, messageY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredString(renderer, runtimeStatus, width / 2, messageY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFF5555 : 0x55FF55);
        } else if (layout.saveY - layout.endpointY >= 52) {
            int infoY = layout.endpointY + 28;
            drawCenteredString(
                    renderer,
                    tr(isOffline()
                            ? "screen.universal_translator.info.offline"
                            : "screen.universal_translator.info.api"),
                    width / 2, infoY, 0xFFAA55);
            drawCenteredString(renderer, tr("screen.universal_translator.info.keybind"),
                    width / 2, infoY + 15, 0xA0A0A0);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private boolean isOffline() {
        return "offline".equalsIgnoreCase(provider);
    }

    private boolean isLlm() {
        return TranslationProviderCatalog.usesLlmEditor(provider);
    }

    private String providerLabel() {
        return TranslationProviderCatalog.displayName(provider);
    }

    private static String nextProvider(String current) {
        return TranslationProviderCatalog.next(current);
    }

    void applyLlmSettings(String endpoint, String model, String apiKey) {
        this.llmEndpoint = endpoint;
        this.llmModel = model;
        this.llmApiKey = apiKey;
    }

    String llmApiKey() {
        return llmApiKey;
    }

    private static String colorLabel(TranslationTextColor color) {
        switch (color) {
            case ORIGINAL: return tr("value.universal_translator.color.original");
            case GREEN: return tr("value.universal_translator.color.green");
            case GOLD: return tr("value.universal_translator.color.gold");
            case LIGHT_PURPLE: return tr("value.universal_translator.color.light_purple");
            case YELLOW: return tr("value.universal_translator.color.yellow");
            case WHITE: return tr("value.universal_translator.color.white");
            case AQUA:
            default: return tr("value.universal_translator.color.aqua");
        }
    }

    private static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }

    private Layout layout() {
        int totalWidth = Math.max(180, Math.min(310, width - 20));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;
        int top = Math.max(20, Math.min(44, 20 + Math.max(0, height - 220) / 4));
        int rowStep = height >= 300 ? 26 : (height >= 260 ? 22 : 20);
        int targetY = top + rowStep * 7 + 2;
        int endpointY = targetY + (height >= 300 ? 32 : 28);
        int saveY = height >= 330 ? 296 : Math.max(endpointY + 22, height - 24);
        return new Layout(left, left + buttonWidth + gap, totalWidth, buttonWidth,
                top, rowStep, targetY, endpointY, saveY);
    }

    private static final class Layout {
        private final int left;
        private final int right;
        private final int totalWidth;
        private final int buttonWidth;
        private final int top;
        private final int rowStep;
        private final int targetY;
        private final int endpointY;
        private final int saveY;

        private Layout(int left, int right, int totalWidth, int buttonWidth,
                       int top, int rowStep, int targetY, int endpointY, int saveY) {
            this.left = left;
            this.right = right;
            this.totalWidth = totalWidth;
            this.buttonWidth = buttonWidth;
            this.top = top;
            this.rowStep = rowStep;
            this.targetY = targetY;
            this.endpointY = endpointY;
            this.saveY = saveY;
        }

        private int row(int index) {
            return top + rowStep * index;
        }
    }
}
