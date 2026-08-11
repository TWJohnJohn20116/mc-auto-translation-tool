package org.universaltranslator.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;

/** Minimal dependency-free settings screen, opened with U by default. */
final class UniversalTranslatorConfigScreen extends Screen {
    private final Screen parent;
    private final FabricConfig original;
    private boolean enabled;
    private boolean translateChat;
    private boolean translateOther;
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
    private TextFieldWidget targetLanguage;
    private TextFieldWidget outgoingTargetLanguage;
    private TextFieldWidget endpoint;
    private ButtonWidget enabledButton;
    private ButtonWidget chatButton;
    private ButtonWidget otherButton;
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
    private String status = "";

    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
        super(Text.translatable("screen.universal_translator.settings.title"));
        this.parent = parent;
        this.original = config;
        this.enabled = config.enabled;
        this.translateChat = config.translateChat;
        this.translateOther = config.translateOther;
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
    protected void init() {
        String targetValue = targetLanguage == null
                ? original.targetLanguage : targetLanguage.getText();
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getText();
        String outgoingTargetValue = outgoingTargetLanguage == null
                ? original.outgoingTargetLanguage : outgoingTargetLanguage.getText();
        Layout layout = layout();
        int left = layout.left;
        this.enabledButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            enabled = !enabled;
            refreshLabels();
        }).dimensions(left, layout.row(0), layout.buttonWidth, 20).build());
        this.cacheButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).dimensions(layout.right, layout.row(0), layout.buttonWidth, 20).build());
        this.chatButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).dimensions(left, layout.row(1), layout.buttonWidth, 20).build());
        this.otherButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).dimensions(layout.right, layout.row(1), layout.buttonWidth, 20).build());
        this.providerButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            provider = nextProvider(provider);
            refreshLabels();
        }).dimensions(left, layout.row(2), layout.buttonWidth, 20).build());
        this.displayButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
            refreshLabels();
        }).dimensions(layout.right, layout.row(2), layout.buttonWidth, 20).build());
        this.mixedTextButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateEnglishOnly = !translateEnglishOnly;
            refreshLabels();
        }).dimensions(left, layout.row(3), layout.buttonWidth, 20).build());
        this.colorButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translatedTextColor = translatedTextColor.next();
            refreshLabels();
        }).dimensions(layout.right, layout.row(3), layout.buttonWidth, 20).build());
        this.downloadButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            if (isLlm()) {
                if (this.client != null) {
                    this.client.setScreen(new UniversalTranslatorLlmConfigScreen(
                            this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
                }
            } else {
                offlineAutoDownload = !offlineAutoDownload;
            }
            refreshLabels();
        }).dimensions(left, layout.row(4), layout.buttonWidth, 20).build());
        this.fallbackButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).dimensions(layout.right, layout.row(4), layout.buttonWidth, 20).build());
        this.modelButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            offlineModel = offlineModel.next();
            refreshLabels();
        }).dimensions(left, layout.row(5), layout.buttonWidth, 20).build());
        this.diagnosticsButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.universal_translator.diagnostics.title"), button -> {
            if (client != null) {
                client.setScreen(new UniversalTranslatorDiagnosticsScreen(this));
            }
        }).dimensions(layout.right, layout.row(5), layout.buttonWidth, 20).build());

        int presetWidth = Math.max(46, Math.min(68, layout.buttonWidth / 2));
        int languageWidth = layout.buttonWidth - presetWidth - 4;
        this.targetLanguage = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, layout.targetY, languageWidth, 20,
                Text.translatable("screen.universal_translator.target_language")));
        this.targetLanguage.setMaxLength(32);
        this.targetLanguage.setText(targetValue);
        this.targetLanguageButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            targetLanguage.setText(TargetLanguage.nextPreset(targetLanguage.getText()));
            refreshLabels();
        }).dimensions(left + languageWidth + 4, layout.targetY, presetWidth, 20).build());
        this.outgoingButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateOutgoing = !translateOutgoing;
            refreshLabels();
        }).dimensions(layout.right, layout.targetY, layout.buttonWidth, 20).build());
        this.endpoint = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, layout.endpointY, layout.buttonWidth, 20,
                Text.translatable("screen.universal_translator.endpoint")));
        this.endpoint.setMaxLength(512);
        this.endpoint.setText(endpointValue);
        this.outgoingTargetLanguage = addDrawableChild(new TextFieldWidget(
                this.textRenderer, layout.right, layout.endpointY, layout.buttonWidth, 20,
                Text.translatable("screen.universal_translator.outgoing_target_language")));
        this.outgoingTargetLanguage.setMaxLength(32);
        this.outgoingTargetLanguage.setText(outgoingTargetValue);

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.universal_translator.save"), button -> saveAndApply())
                .dimensions(left, layout.saveY, layout.buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
                .dimensions(layout.right, layout.saveY, layout.buttonWidth, 20).build());
        refreshLabels();
    }

    private void refreshLabels() {
        enabledButton.setMessage(Text.translatable("screen.universal_translator.option.automatic", onOff(enabled)));
        chatButton.setMessage(Text.translatable("screen.universal_translator.option.chat", onOff(translateChat)));
        otherButton.setMessage(Text.translatable("screen.universal_translator.option.other", onOff(translateOther)));
        cacheButton.setMessage(Text.translatable("screen.universal_translator.option.cache", onOff(diskCache)));
        providerButton.setMessage(Text.translatable("screen.universal_translator.option.provider", providerLabel()));
        displayButton.setMessage(Text.translatable("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        mixedTextButton.setMessage(Text.translatable("screen.universal_translator.option.mixed", onOff(translateEnglishOnly)));
        colorButton.setMessage(Text.translatable("screen.universal_translator.option.color", colorLabel(translatedTextColor)));
        downloadButton.setMessage(isLlm()
                ? Text.translatable("screen.universal_translator.option.llm_settings")
                : Text.translatable("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        modelButton.setMessage(Text.translatable("screen.universal_translator.option.model", offlineModel.displayName()));
        fallbackButton.setMessage(Text.translatable("screen.universal_translator.option.fallback", onOff(apiFallback)));
        outgoingButton.setMessage(Text.translatable("screen.universal_translator.option.outgoing", onOff(translateOutgoing)));
        targetLanguageButton.setMessage(Text.translatable("screen.universal_translator.option.target_preset",
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
                    translateOutgoing,
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
            close();
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFF);
        Layout layout = layout();
        int left = layout.left;
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("screen.universal_translator.target_language_hint"),
                left, layout.targetY - 11, 0xA0A0A0);
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("screen.universal_translator.endpoint_hint"),
                left, layout.endpointY - 11, 0xA0A0A0);
        context.drawTextWithShadow(this.textRenderer,
                Text.translatable("screen.universal_translator.outgoing_target_hint"),
                layout.right, layout.endpointY - 11, 0xA0A0A0);
        String rawRuntimeStatus = FabricTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                UniversalTranslatorConfigScreen::tr);
        int belowSave = layout.saveY + 28;
        int messageY = belowSave <= this.height - 10 ? belowSave : layout.saveY - 14;
        if (!status.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status),
                    this.width / 2, messageY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(runtimeStatus),
                    this.width / 2, messageY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFF5555 : 0x55FF55);
        } else if (layout.saveY - layout.endpointY >= 52) {
            int infoY = layout.endpointY + 28;
            context.drawCenteredTextWithShadow(
                    this.textRenderer,
                    Text.translatable(isOffline()
                            ? "screen.universal_translator.info.offline"
                            : "screen.universal_translator.info.api"),
                    this.width / 2, infoY, 0xFFAA55);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("screen.universal_translator.info.keybind"),
                    this.width / 2, infoY + 15, 0xA0A0A0);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private boolean isTencent() {
        return "tencent-hunyuan".equalsIgnoreCase(provider);
    }

    private boolean isOffline() {
        return "offline".equalsIgnoreCase(provider);
    }

    private boolean isLlm() {
        return "openai-compatible".equalsIgnoreCase(provider);
    }

    private String providerLabel() {
        return isOffline() ? tr("value.universal_translator.provider_offline")
                : (isTencent() ? tr("value.universal_translator.provider_tencent")
                : (isLlm() ? tr("value.universal_translator.provider_llm") : "Libre"));
    }

    private static String nextProvider(String current) {
        if ("offline".equalsIgnoreCase(current)) {
            return "libretranslate";
        }
        if ("libretranslate".equalsIgnoreCase(current)) {
            return "tencent-hunyuan";
        }
        if ("tencent-hunyuan".equalsIgnoreCase(current)) {
            return "openai-compatible";
        }
        return "offline";
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
        return Text.translatable(key, arguments).getString();
    }

    private Layout layout() {
        int totalWidth = Math.max(180, Math.min(310, this.width - 20));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;
        int top = Math.max(20, Math.min(44, 20 + Math.max(0, this.height - 220) / 4));
        int rowStep = this.height >= 300 ? 26 : (this.height >= 260 ? 22 : 20);
        int targetY = top + rowStep * 6 + 2;
        int endpointY = targetY + (this.height >= 300 ? 32 : 28);
        int saveY = this.height >= 330 ? 270 : Math.max(endpointY + 22, this.height - 24);
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
