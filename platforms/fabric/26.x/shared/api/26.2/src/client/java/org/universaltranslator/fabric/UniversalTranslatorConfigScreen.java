package org.universaltranslator.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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
    private String targetLanguage;
    private String outgoingTargetLanguage;
    private EditBox endpoint;
    private EditBox blockedKeywords;
    private Button enabledButton;
    private Button chatButton;
    private Button otherButton;
    private Button vanillaButton;
    private Button playerNamesButton;
    private Button cacheButton;
    private Button providerButton;
    private Button displayButton;
    private Button downloadButton;
    private Button modelButton;
    private Button fallbackButton;
    private Button diagnosticsButton;
    private Button mixedTextButton;
    private Button colorButton;
    private Button outgoingButton;
    private Button targetLanguageButton;
    private Button outgoingTargetLanguageButton;
    private String status = "";

    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
        super(Component.translatable("screen.universal_translator.settings.title"));
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
        if (targetLanguage == null) {
            targetLanguage = TargetLanguage.canonicalize(original.targetLanguage);
        }
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getValue();
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getValue();
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }
        Layout layout = layout();
        int left = layout.left;
        this.enabledButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            enabled = !enabled;
            refreshLabels();
        }).bounds(left, layout.row(0), layout.buttonWidth, 20).build());
        this.cacheButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).bounds(layout.right, layout.row(0), layout.buttonWidth, 20).build());
        this.chatButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).bounds(left, layout.row(1), layout.buttonWidth, 20).build());
        this.otherButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).bounds(layout.right, layout.row(1), layout.buttonWidth, 20).build());
        this.providerButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            provider = nextProvider(provider);
            refreshLabels();
        }).bounds(left, layout.row(2), layout.buttonWidth, 20).build());
        this.displayButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
            refreshLabels();
        }).bounds(layout.right, layout.row(2), layout.buttonWidth, 20).build());
        this.mixedTextButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateEnglishOnly = !translateEnglishOnly;
            refreshLabels();
        }).bounds(left, layout.row(3), layout.buttonWidth, 20).build());
        this.colorButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translatedTextColor = translatedTextColor.next();
            refreshLabels();
        }).bounds(layout.right, layout.row(3), layout.buttonWidth, 20).build());
        this.downloadButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            if (isLlm()) {
                if (this.minecraft != null) {
                    this.minecraft.gui.setScreen(new UniversalTranslatorLlmConfigScreen(
                            this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
                }
            } else {
                offlineAutoDownload = !offlineAutoDownload;
            }
            refreshLabels();
        }).bounds(left, layout.row(4), layout.buttonWidth, 20).build());
        this.fallbackButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).bounds(layout.right, layout.row(4), layout.buttonWidth, 20).build());
        this.modelButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            offlineModel = offlineModel.next();
            refreshLabels();
        }).bounds(left, layout.row(5), layout.buttonWidth, 20).build());
        this.blockedKeywords = addRenderableWidget(new EditBox(
                this.font, layout.right, layout.row(5), layout.buttonWidth, 20,
                Component.translatable("screen.universal_translator.blocked_keywords")));
        this.blockedKeywords.setMaxLength(4096);
        this.blockedKeywords.setValue(blockedKeywordsValue);
        this.blockedKeywords.setHint(Component.translatable(
                "screen.universal_translator.blocked_keywords_hint"));
        int compactGap = 4;
        int compactWidth = (layout.totalWidth - compactGap * 2) / 3;
        int compactMiddle = left + compactWidth + compactGap;
        int compactRight = compactMiddle + compactWidth + compactGap;
        this.diagnosticsButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.title"), button -> {
            if (minecraft != null) {
                minecraft.gui.setScreen(new UniversalTranslatorDiagnosticsScreen(this));
            }
        }).bounds(compactRight, layout.row(6), compactWidth, 20).build());
        this.vanillaButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateVanilla = !translateVanilla;
            refreshLabels();
        }).bounds(left, layout.row(6), compactWidth, 20).build());
        this.playerNamesButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translatePlayerNames = !translatePlayerNames;
            refreshLabels();
        }).bounds(compactMiddle, layout.row(6), compactWidth, 20).build());

        this.targetLanguageButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            targetLanguage = TargetLanguage.nextPreset(targetLanguage);
            refreshLabels();
        }).bounds(left, layout.targetY, layout.buttonWidth, 20).build());
        this.outgoingButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateOutgoing = !translateOutgoing;
            refreshLabels();
        }).bounds(layout.right, layout.targetY, layout.buttonWidth, 20).build());
        this.endpoint = addRenderableWidget(new EditBox(
                this.font, left, layout.endpointY, layout.buttonWidth, 20,
                Component.translatable("screen.universal_translator.endpoint")));
        this.endpoint.setMaxLength(512);
        this.endpoint.setValue(endpointValue);
        this.outgoingTargetLanguageButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            outgoingTargetLanguage = TargetLanguage.nextPreset(outgoingTargetLanguage);
            refreshLabels();
        }).bounds(layout.right, layout.endpointY, layout.buttonWidth, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.universal_translator.save"), button -> saveAndApply())
                .bounds(left, layout.saveY, layout.buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(layout.right, layout.saveY, layout.buttonWidth, 20).build());
        refreshLabels();
    }

    private void refreshLabels() {
        enabledButton.setMessage(Component.translatable("screen.universal_translator.option.automatic", onOff(enabled)));
        chatButton.setMessage(Component.translatable("screen.universal_translator.option.chat", onOff(translateChat)));
        otherButton.setMessage(Component.translatable("screen.universal_translator.option.other", onOff(translateOther)));
        vanillaButton.setMessage(Component.translatable("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        playerNamesButton.setMessage(Component.translatable(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames)));
        cacheButton.setMessage(Component.translatable("screen.universal_translator.option.cache", onOff(diskCache)));
        providerButton.setMessage(Component.translatable("screen.universal_translator.option.provider", providerLabel()));
        displayButton.setMessage(Component.translatable("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        mixedTextButton.setMessage(Component.translatable("screen.universal_translator.option.mixed", onOff(translateEnglishOnly)));
        colorButton.setMessage(Component.translatable("screen.universal_translator.option.color", colorLabel(translatedTextColor)));
        downloadButton.setMessage(isLlm()
                ? Component.translatable("screen.universal_translator.option.llm_settings")
                : Component.translatable("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        modelButton.setMessage(Component.translatable("screen.universal_translator.option.model", offlineModel.displayName()));
        fallbackButton.setMessage(Component.translatable("screen.universal_translator.option.fallback", onOff(apiFallback)));
        outgoingButton.setMessage(Component.translatable("screen.universal_translator.option.outgoing", onOff(translateOutgoing)));
        targetLanguageButton.setMessage(Component.translatable("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage)));
        outgoingTargetLanguageButton.setMessage(Component.translatable(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage)));
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
            FabricConfig updated = original.withSettings(
                    enabled,
                    translateChat,
                    translateOther,
                    translateVanilla,
                    translateOutgoing,
                    translatePlayerNames,
                    blockedKeywords.getValue(),
                    targetLanguage,
                    outgoingTargetLanguage,
                    displayMode,
                    translateEnglishOnly,
                    translatedTextColor,
                    provider,
                    endpoint.getValue(),
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        Layout layout = layout();
        int left = layout.left;
        graphics.text(this.font, Component.translatable("screen.universal_translator.target_language_hint"),
                left, layout.targetY - 11, 0xA0A0A0);
        graphics.text(this.font,
                Component.translatable("screen.universal_translator.endpoint_hint"),
                left, layout.endpointY - 11, 0xA0A0A0);
        graphics.text(this.font,
                Component.translatable("screen.universal_translator.outgoing_target_hint"),
                layout.right, layout.endpointY - 11, 0xA0A0A0);
        String rawRuntimeStatus = FabricTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                UniversalTranslatorConfigScreen::tr);
        int belowSave = layout.saveY + 28;
        int messageY = belowSave <= this.height - 10 ? belowSave : layout.saveY - 14;
        if (!status.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(status),
                    this.width / 2, messageY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(runtimeStatus),
                    this.width / 2, messageY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFF5555 : 0x55FF55);
        } else if (layout.saveY - layout.endpointY >= 52) {
            int infoY = layout.endpointY + 28;
            graphics.centeredText(
                    this.font,
                    Component.translatable(isOffline()
                            ? "screen.universal_translator.info.offline"
                            : "screen.universal_translator.info.api"),
                    this.width / 2, infoY, 0xFFAA55);
            graphics.centeredText(this.font,
                    Component.translatable("screen.universal_translator.info.keybind"),
                    this.width / 2, infoY + 15, 0xA0A0A0);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
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
        return Component.translatable(key, arguments).getString();
    }

    private Layout layout() {
        int totalWidth = Math.max(180, Math.min(310, this.width - 20));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;
        int top = Math.max(20, Math.min(44, 20 + Math.max(0, this.height - 220) / 4));
        int rowStep = this.height >= 300 ? 26 : (this.height >= 260 ? 22 : 20);
        int targetY = top + rowStep * 7 + 2;
        int endpointY = targetY + (this.height >= 300 ? 32 : 28);
        int saveY = this.height >= 330 ? 296 : Math.max(endpointY + 22, this.height - 24);
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
