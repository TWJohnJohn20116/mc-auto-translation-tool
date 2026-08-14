package org.universaltranslator.fabric;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.text.LiteralText;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationProviderCatalog;

/** Minimal dependency-free settings screen, opened with U by default. */
final class UniversalTranslatorConfigScreen extends Screen {
    private final Screen parent;
    private final FabricConfig original;
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
    private TextFieldWidget targetLanguage;
    private TextFieldWidget outgoingTargetLanguage;
    private TextFieldWidget endpoint;
    private TextFieldWidget blockedKeywords;
    private ButtonWidget enabledButton;
    private ButtonWidget chatButton;
    private ButtonWidget otherButton;
    private ButtonWidget vanillaButton;
    private ButtonWidget cacheButton;
    private ButtonWidget providerButton;
    private ButtonWidget displayButton;
    private ButtonWidget downloadButton;
    private ButtonWidget modelButton;
    private ButtonWidget fallbackButton;
    private ButtonWidget diagnosticsButton;
    private ButtonWidget mixedTextButton;
    private ButtonWidget colorButton;
    private ButtonWidget outgoingButton;
    private ButtonWidget targetLanguageButton;
    private ButtonWidget playerNamesButton;
    private ButtonWidget translationTabButton;
    private ButtonWidget serviceTabButton;
    private ButtonWidget advancedTabButton;
    private SettingsPage page = SettingsPage.TRANSLATION;
    private String status = "";

    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
        super(new TranslatableText("screen.universal_translator.settings.title"));
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
    protected void init() {
        String targetValue = targetLanguage == null
                ? original.targetLanguage : targetLanguage.getText();
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getText();
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getText();
        String outgoingTargetValue = outgoingTargetLanguage == null
                ? original.outgoingTargetLanguage : outgoingTargetLanguage.getText();

        Layout layout = layout();
        int left = layout.left;

        this.translationTabButton = addDrawableChild(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.tab.translation"),
                button -> selectPage(SettingsPage.TRANSLATION))
                .dimensions(left, layout.tabY, layout.tabWidth, 20).build());
        this.serviceTabButton = addDrawableChild(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.tab.service"),
                button -> selectPage(SettingsPage.SERVICE))
                .dimensions(layout.middleTab, layout.tabY, layout.tabWidth, 20).build());
        this.advancedTabButton = addDrawableChild(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.tab.advanced"),
                button -> selectPage(SettingsPage.ADVANCED))
                .dimensions(layout.rightTab, layout.tabY, layout.tabWidth, 20).build());

        // Translation page
        this.enabledButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            enabled = !enabled;
            refreshLabels();
        }).dimensions(left, layout.row(0), layout.buttonWidth, 20).build());
        int presetWidth = Math.max(46, Math.min(68, layout.buttonWidth / 2));
        int languageWidth = layout.buttonWidth - presetWidth - 4;
        this.targetLanguage = addDrawableChild(new TextFieldWidget(
                this.textRenderer, layout.right, layout.row(0), languageWidth, 20,
                new TranslatableText("screen.universal_translator.target_language")));
        this.targetLanguage.setMaxLength(32);
        this.targetLanguage.setText(targetValue);
        this.targetLanguageButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            targetLanguage.setText(TargetLanguage.nextPreset(targetLanguage.getText()));
            refreshLabels();
        }).dimensions(layout.right + languageWidth + 4, layout.row(0), presetWidth, 20).build());
        this.chatButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).dimensions(left, layout.row(1), layout.buttonWidth, 20).build());
        this.otherButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).dimensions(layout.right, layout.row(1), layout.buttonWidth, 20).build());
        this.vanillaButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateVanilla = !translateVanilla;
            refreshLabels();
        }).dimensions(left, layout.row(2), layout.buttonWidth, 20).build());
        this.displayButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
            refreshLabels();
        }).dimensions(layout.right, layout.row(2), layout.buttonWidth, 20).build());
        this.mixedTextButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateEnglishOnly = !translateEnglishOnly;
            refreshLabels();
        }).dimensions(left, layout.row(3), layout.buttonWidth, 20).build());
        this.colorButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translatedTextColor = translatedTextColor.next();
            refreshLabels();
        }).dimensions(layout.right, layout.row(3), layout.buttonWidth, 20).build());

        // Service page
        this.providerButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            provider = nextProvider(provider);
            refreshLabels();
        }).dimensions(left, layout.row(0), layout.buttonWidth, 20).build());
        this.cacheButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).dimensions(layout.right, layout.row(0), layout.buttonWidth, 20).build());
        this.downloadButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            if (isLlm()) {
                if (this.client != null) {
                    this.client.setScreen(new UniversalTranslatorLlmConfigScreen(
                            this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
                }
            } else {
                offlineAutoDownload = !offlineAutoDownload;
            }
            refreshLabels();
        }).dimensions(left, layout.row(1), layout.buttonWidth, 20).build());
        this.modelButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            offlineModel = offlineModel.next();
            refreshLabels();
        }).dimensions(layout.right, layout.row(1), layout.buttonWidth, 20).build());
        this.fallbackButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).dimensions(left, layout.row(2), layout.buttonWidth, 20).build());
        this.diagnosticsButton = addDrawableChild(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.diagnostics.title"), button -> {
            if (client != null) {
                client.setScreen(new UniversalTranslatorDiagnosticsScreen(this));
            }
        }).dimensions(layout.right, layout.row(2), layout.buttonWidth, 20).build());
        this.endpoint = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, layout.endpointY, layout.totalWidth, 20,
                new TranslatableText("screen.universal_translator.endpoint")));
        this.endpoint.setMaxLength(512);
        this.endpoint.setText(endpointValue);

        // Advanced page
        this.outgoingButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateOutgoing = !translateOutgoing;
            refreshLabels();
        }).dimensions(left, layout.row(0), layout.buttonWidth, 20).build());
        this.outgoingTargetLanguage = addDrawableChild(new TextFieldWidget(
                this.textRenderer, layout.right, layout.row(0), layout.buttonWidth, 20,
                new TranslatableText("screen.universal_translator.outgoing_target_language")));
        this.outgoingTargetLanguage.setMaxLength(32);
        this.outgoingTargetLanguage.setText(outgoingTargetValue);
        this.playerNamesButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translatePlayerNames = !translatePlayerNames;
            refreshLabels();
        }).dimensions(left, layout.row(1), layout.buttonWidth, 20).build());
        this.blockedKeywords = addDrawableChild(new TextFieldWidget(
                this.textRenderer, layout.right, layout.row(1), layout.buttonWidth, 20,
                new TranslatableText("screen.universal_translator.blocked_keywords")));
        this.blockedKeywords.setMaxLength(4096);
        this.blockedKeywords.setText(blockedKeywordsValue);
        this.blockedKeywords.setSuggestion(tr("screen.universal_translator.blocked_keywords_hint"));

        addDrawableChild(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.save"), button -> saveAndApply())
                .dimensions(left, layout.saveY, layout.buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(new TranslatableText("gui.cancel"), button -> onClose())
                .dimensions(layout.right, layout.saveY, layout.buttonWidth, 20).build());
        refreshLabels();
        updatePageVisibility();
    }

    private void selectPage(SettingsPage nextPage) {
        this.page = nextPage;
        this.status = "";
        updatePageVisibility();
    }

    private void updatePageVisibility() {
        boolean translation = page == SettingsPage.TRANSLATION;
        boolean service = page == SettingsPage.SERVICE;
        boolean advanced = page == SettingsPage.ADVANCED;

        translationTabButton.active = !translation;
        serviceTabButton.active = !service;
        advancedTabButton.active = !advanced;

        enabledButton.visible = translation;
        targetLanguage.visible = translation;
        targetLanguageButton.visible = translation;
        chatButton.visible = translation;
        otherButton.visible = translation;
        vanillaButton.visible = translation;
        displayButton.visible = translation;
        mixedTextButton.visible = translation;
        colorButton.visible = translation;

        providerButton.visible = service;
        cacheButton.visible = service;
        downloadButton.visible = service;
        modelButton.visible = service;
        fallbackButton.visible = service;
        diagnosticsButton.visible = service;
        endpoint.visible = service;

        outgoingButton.visible = advanced;
        outgoingTargetLanguage.visible = advanced;
        playerNamesButton.visible = advanced;
        blockedKeywords.visible = advanced;
    }

    private void refreshLabels() {
        enabledButton.setMessage(new TranslatableText("screen.universal_translator.option.automatic", onOff(enabled)));
        chatButton.setMessage(new TranslatableText("screen.universal_translator.option.chat", onOff(translateChat)));
        vanillaButton.setMessage(new TranslatableText("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        otherButton.setMessage(new TranslatableText("screen.universal_translator.option.other", onOff(translateOther)));
        cacheButton.setMessage(new TranslatableText("screen.universal_translator.option.cache", onOff(diskCache)));
        providerButton.setMessage(new TranslatableText("screen.universal_translator.option.provider", providerLabel()));
        displayButton.setMessage(new TranslatableText("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        mixedTextButton.setMessage(new TranslatableText("screen.universal_translator.option.mixed", onOff(translateEnglishOnly)));
        colorButton.setMessage(new TranslatableText("screen.universal_translator.option.color", colorLabel(translatedTextColor)));
        downloadButton.setMessage(isLlm()
                ? new TranslatableText("screen.universal_translator.option.llm_settings")
                : new TranslatableText("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        modelButton.setMessage(new TranslatableText("screen.universal_translator.option.model", offlineModel.displayName()));
        fallbackButton.setMessage(new TranslatableText("screen.universal_translator.option.fallback", onOff(apiFallback)));
        outgoingButton.setMessage(new TranslatableText("screen.universal_translator.option.outgoing", onOff(translateOutgoing)));
        playerNamesButton.setMessage(new TranslatableText(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames)));
        targetLanguageButton.setMessage(new TranslatableText("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage.getText())));
        downloadButton.active = isOffline() || isLlm();
        modelButton.active = isOffline();
        fallbackButton.active = isOffline();
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
            if (targetLanguage.getText().trim().isEmpty()) {
                throw new IllegalArgumentException(tr("error.universal_translator.target_required"));
            }
            if (translateOutgoing && outgoingTargetLanguage.getText().trim().isEmpty()) {
                throw new IllegalArgumentException(tr("error.universal_translator.outgoing_target_required"));
            }
            FabricConfig updated = original.withSettings(
                    enabled,
                    translateChat,
                    translateOther,
                    translateVanilla,
                    translateOutgoing,
                    translatePlayerNames,
                    blockedKeywords.getText(),
                    targetLanguage.getText(),
                    outgoingTargetLanguage.getText(),
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
            FabricTranslationRuntime.initialize(updated);
            updated.save();
            status = tr("status.universal_translator.saved");
            onClose();
        } catch (Exception exception) {
            if (runtimeChanged) {
                try {
                    FabricTranslationRuntime.initialize(original);
                } catch (Exception restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            status = tr("status.universal_translator.save_failed", exception.getMessage());
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        Layout layout = layout();
        drawCenteredText(matrices, this.textRenderer, this.title,
                this.width / 2, layout.titleY, 0xFFFFFF);
        drawCenteredText(matrices, this.textRenderer,
                new TranslatableText(page.descriptionKey),
                this.width / 2, layout.descriptionY, 0xA0A0A0);
        if (page == SettingsPage.SERVICE) {
            drawTextWithShadow(matrices, this.textRenderer,
                    new TranslatableText("screen.universal_translator.endpoint_hint"),
                    layout.left, layout.endpointY - 11, 0xA0A0A0);
        }

        String rawRuntimeStatus = FabricTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                UniversalTranslatorConfigScreen::tr);
        if (!status.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, new LiteralText(status),
                    this.width / 2, layout.statusY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, new LiteralText(runtimeStatus),
                    this.width / 2, layout.statusY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFF5555 : 0x55FF55);
        } else {
            drawCenteredText(matrices, this.textRenderer,
                    new TranslatableText(isOffline() ? "screen.universal_translator.info.offline" : "screen.universal_translator.info.api"),
                    this.width / 2, layout.statusY, 0xFFAA55);
        }
        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    public boolean isPauseScreen() {
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
        return new TranslatableText(key, arguments).getString();
    }

    private Layout layout() {
        int totalWidth = Math.max(220, Math.min(460, this.width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;
        int titleY = this.height < 260 ? 8 : 14;
        int tabY = titleY + 17;
        int tabGap = 4;
        int tabWidth = (totalWidth - tabGap * 2) / 3;
        int descriptionY = tabY + 28;
        int top = descriptionY + 16;
        int rowStep = this.height >= 280 ? 26 : 23;
        int endpointY = top + rowStep * 4;
        int minimumSaveY = endpointY + 28;
        int saveY = Math.min(this.height - 42, minimumSaveY);
        int statusY = Math.min(this.height - 10, saveY + 27);
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
