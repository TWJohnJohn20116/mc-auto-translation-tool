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
    private static final int PLAYER_NAMES = 19;
    private static final int TAB_TRANSLATION = 20;
    private static final int TAB_SERVICE = 21;
    private static final int TAB_ADVANCED = 22;

    private final GuiScreen parent;
    private final LegacyConfig original;
    private boolean enabled;
    private boolean translateChat;
    private boolean translateOther;
    private boolean translateVanilla;
    private boolean translateOutgoing;
    private boolean translatePlayerNames;
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
    private GuiTextField blockedKeywords;
    private FontRenderer renderer;
    private SettingsPage page = SettingsPage.TRANSLATION;
    private String status = "";

    LegacyConfigScreen(GuiScreen parent, LegacyConfig config) {
        this.parent = parent;
        this.original = config;
        this.enabled = config.enabled;
        this.translateChat = config.translateChat;
        this.translateOther = config.translateOther;
        this.translateVanilla = config.translateVanilla;
        this.translateOutgoing = config.translateOutgoing;
        this.translatePlayerNames = config.translatePlayerNames;
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
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getText();
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }

        buttonList.clear();
        renderer = LegacyVersionAccess.fontRenderer();
        Layout layout = layout();
        int left = layout.left;

        buttonList.add(new GuiButton(TAB_TRANSLATION, left, layout.tabY,
                layout.tabWidth, 20, tr("screen.universal_translator.tab.translation")));
        buttonList.add(new GuiButton(TAB_SERVICE, layout.middleTab, layout.tabY,
                layout.tabWidth, 20, tr("screen.universal_translator.tab.service")));
        buttonList.add(new GuiButton(TAB_ADVANCED, layout.rightTab, layout.tabY,
                layout.tabWidth, 20, tr("screen.universal_translator.tab.advanced")));

        // Translation page
        buttonList.add(new GuiButton(ENABLED, left, layout.row(0), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(TARGET_LANGUAGE, layout.right, layout.row(0),
                layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(CHAT, left, layout.row(1), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(OTHER, layout.right, layout.row(1), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(VANILLA, left, layout.row(2), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(DISPLAY, layout.right, layout.row(2), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(MIXED_TEXT, left, layout.row(3), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(COLOR, layout.right, layout.row(3), layout.buttonWidth, 20, ""));

        // Service page
        buttonList.add(new GuiButton(PROVIDER, left, layout.row(0), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(CACHE, layout.right, layout.row(0), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(DOWNLOAD, left, layout.row(1), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(MODEL, layout.right, layout.row(1), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(FALLBACK, left, layout.row(2), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(DIAGNOSTICS, layout.right, layout.row(2),
                layout.buttonWidth, 20, tr("screen.universal_translator.diagnostics.title")));
        endpoint = new GuiTextField(21, renderer, left, layout.endpointY, layout.totalWidth, 20);
        endpoint.setMaxStringLength(512);
        endpoint.setText(endpointValue);

        // Advanced page
        buttonList.add(new GuiButton(OUTGOING, left, layout.row(0), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(OUTGOING_TARGET_LANGUAGE, layout.right, layout.row(0),
                layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(PLAYER_NAMES, left, layout.row(1),
                layout.buttonWidth, 20, ""));
        blockedKeywords = new GuiTextField(22, renderer, layout.right, layout.row(1),
                layout.buttonWidth, 20);
        blockedKeywords.setMaxStringLength(4096);
        blockedKeywords.setText(blockedKeywordsValue);

        buttonList.add(new GuiButton(SAVE, left, layout.saveY, layout.buttonWidth, 20,
                tr("screen.universal_translator.save")));
        buttonList.add(new GuiButton(CANCEL, layout.right, layout.saveY, layout.buttonWidth, 20,
                tr("gui.cancel")));
        refreshLabels();
        updatePageVisibility();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == TAB_TRANSLATION) {
            selectPage(SettingsPage.TRANSLATION);
            return;
        } else if (button.id == TAB_SERVICE) {
            selectPage(SettingsPage.SERVICE);
            return;
        } else if (button.id == TAB_ADVANCED) {
            selectPage(SettingsPage.ADVANCED);
            return;
        } else if (button.id == ENABLED) {
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
        } else if (button.id == PLAYER_NAMES) {
            translatePlayerNames = !translatePlayerNames;
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

    private void selectPage(SettingsPage nextPage) {
        page = nextPage;
        status = "";
        updatePageVisibility();
    }

    private void updatePageVisibility() {
        boolean translation = page == SettingsPage.TRANSLATION;
        boolean service = page == SettingsPage.SERVICE;
        boolean advanced = page == SettingsPage.ADVANCED;

        button(TAB_TRANSLATION).enabled = !translation;
        button(TAB_SERVICE).enabled = !service;
        button(TAB_ADVANCED).enabled = !advanced;

        setVisible(translation, ENABLED, TARGET_LANGUAGE, CHAT, OTHER,
                VANILLA, DISPLAY, MIXED_TEXT, COLOR);
        setVisible(service, PROVIDER, CACHE, DOWNLOAD, MODEL, FALLBACK, DIAGNOSTICS);
        setVisible(advanced, OUTGOING, OUTGOING_TARGET_LANGUAGE, PLAYER_NAMES);
    }

    private void setVisible(boolean visible, int... ids) {
        for (int id : ids) {
            button(id).visible = visible;
        }
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
        button(PLAYER_NAMES).displayString = tr(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames));
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
                    translatePlayerNames,
                    blockedKeywords.getText(),
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
        if (page == SettingsPage.SERVICE) {
            endpoint.updateCursorCounter();
        } else if (page == SettingsPage.ADVANCED) {
            blockedKeywords.updateCursorCounter();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (page == SettingsPage.SERVICE && endpoint.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (page == SettingsPage.ADVANCED
                && blockedKeywords.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (page == SettingsPage.SERVICE) {
            endpoint.mouseClicked(mouseX, mouseY, mouseButton);
        } else if (page == SettingsPage.ADVANCED) {
            blockedKeywords.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        Layout layout = layout();
        drawCenteredString(renderer, tr("screen.universal_translator.settings.title"),
                width / 2, layout.titleY, 0xFFFFFF);
        drawCenteredString(renderer, tr(page.descriptionKey),
                width / 2, layout.descriptionY, 0xA0A0A0);

        if (page == SettingsPage.SERVICE) {
            drawString(renderer, tr("screen.universal_translator.endpoint_hint"),
                    layout.left, layout.endpointY - 11, 0xA0A0A0);
            endpoint.drawTextBox();
        } else if (page == SettingsPage.ADVANCED) {
            blockedKeywords.drawTextBox();
            if (blockedKeywords.getText().isEmpty() && !blockedKeywords.isFocused()) {
                drawString(renderer, tr("screen.universal_translator.blocked_keywords_hint"),
                        layout.right + 4, layout.row(1) + 6, 0x808080);
            }
        }

        String rawRuntimeStatus = LegacyTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                LegacyConfigScreen::tr);
        if (!status.isEmpty()) {
            drawCenteredString(renderer, status, width / 2, layout.statusY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredString(renderer, runtimeStatus, width / 2, layout.statusY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFF5555 : 0x55FF55);
        } else {
            drawCenteredString(renderer,
                    tr(isOffline()
                            ? "screen.universal_translator.info.offline"
                            : "screen.universal_translator.info.api"),
                    width / 2, layout.statusY, 0xFFAA55);
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
        int totalWidth = Math.max(220, Math.min(460, width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;
        int titleY = height < 260 ? 8 : 14;
        int tabY = titleY + 17;
        int tabGap = 4;
        int tabWidth = (totalWidth - tabGap * 2) / 3;
        int descriptionY = tabY + 28;
        int top = descriptionY + 16;
        int rowStep = height >= 280 ? 26 : 23;
        int endpointY = top + rowStep * 4;
        int minimumSaveY = endpointY + 28;
        int saveY = Math.min(height - 42, minimumSaveY);
        int statusY = Math.min(height - 10, saveY + 27);
        return new Layout(left, left + buttonWidth + gap, totalWidth, buttonWidth,
                titleY, tabY, left + tabWidth + tabGap,
                left + (tabWidth + tabGap) * 2, tabWidth,
                descriptionY, top, rowStep, endpointY, saveY, statusY);
    }

    private static final class Layout {
        private final int left;
        private final int right;
        private final int totalWidth;
        private final int buttonWidth;
        private final int titleY;
        private final int tabY;
        private final int middleTab;
        private final int rightTab;
        private final int tabWidth;
        private final int descriptionY;
        private final int top;
        private final int rowStep;
        private final int endpointY;
        private final int saveY;
        private final int statusY;

        private Layout(int left, int right, int totalWidth, int buttonWidth,
                       int titleY, int tabY, int middleTab, int rightTab, int tabWidth,
                       int descriptionY, int top, int rowStep, int endpointY,
                       int saveY, int statusY) {
            this.left = left;
            this.right = right;
            this.totalWidth = totalWidth;
            this.buttonWidth = buttonWidth;
            this.titleY = titleY;
            this.tabY = tabY;
            this.middleTab = middleTab;
            this.rightTab = rightTab;
            this.tabWidth = tabWidth;
            this.descriptionY = descriptionY;
            this.top = top;
            this.rowStep = rowStep;
            this.endpointY = endpointY;
            this.saveY = saveY;
            this.statusY = statusY;
        }

        private int row(int index) {
            return top + rowStep * index;
        }
    }

    private enum SettingsPage {
        TRANSLATION("screen.universal_translator.tab.translation.description"),
        SERVICE("screen.universal_translator.tab.service.description"),
        ADVANCED("screen.universal_translator.tab.advanced.description");

        private final String descriptionKey;

        SettingsPage(String descriptionKey) {
            this.descriptionKey = descriptionKey;
        }
    }

}
