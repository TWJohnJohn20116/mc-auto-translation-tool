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
import org.universaltranslator.core.SettingsUiAnimation;
import org.universaltranslator.core.SettingsSelectionList;

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
    private boolean animatedUi;
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
    private TextFieldWidget endpoint;
    private TextFieldWidget blockedKeywords;
    private ButtonWidget enabledButton;
    private ButtonWidget uiStyleButton;
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
    private ButtonWidget outgoingTargetLanguageButton;
    private ButtonWidget playerNamesButton;
    private String status = "";
    private long animationStartedNanos = System.nanoTime();
    private SettingsSelectionList.Kind openSelection = SettingsSelectionList.Kind.NONE;

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
        this.animatedUi = config.animatedUi;
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
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getText();
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getText();
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }
        Layout layout = layout();
        int left = layout.left;
        int styleWidth = Math.min(86, layout.buttonWidth);
        this.uiStyleButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
            refreshLabels();
        }).dimensions(Math.max(4, this.width - styleWidth - 6), 6, styleWidth, 20).build());
        this.enabledButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            enabled = !enabled;
            refreshLabels();
        }).dimensions(left, layout.row(0), layout.buttonWidth, 20).build());
        this.cacheButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).dimensions(layout.right, layout.row(0), layout.buttonWidth, 20).build());
        this.chatButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).dimensions(left, layout.row(1), layout.buttonWidth, 20).build());
        this.otherButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).dimensions(layout.right, layout.row(1), layout.buttonWidth, 20).build());
        this.providerButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            openSelection = SettingsSelectionList.Kind.PROVIDER;
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
        }).dimensions(left, layout.row(4), layout.buttonWidth, 20).build());
        this.fallbackButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).dimensions(layout.right, layout.row(4), layout.buttonWidth, 20).build());
        this.modelButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            offlineModel = offlineModel.next();
            refreshLabels();
        }).dimensions(left, layout.row(5), layout.buttonWidth, 20).build());
        this.diagnosticsButton = addDrawableChild(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.diagnostics.title"), button -> {
            if (client != null) {
                client.setScreen(new UniversalTranslatorDiagnosticsScreen(this));
            }
        }).dimensions(layout.right, layout.row(5), layout.buttonWidth, 20).build());
        this.playerNamesButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translatePlayerNames = !translatePlayerNames;
            refreshLabels();
        }).dimensions(left, layout.row(6), layout.buttonWidth, 20).build());
        this.blockedKeywords = addDrawableChild(new TextFieldWidget(
                this.textRenderer, layout.right, layout.row(6), layout.buttonWidth, 20,
                new TranslatableText("screen.universal_translator.blocked_keywords")));
        this.blockedKeywords.setMaxLength(4096);
        this.blockedKeywords.setText(blockedKeywordsValue);
        this.blockedKeywords.setSuggestion(tr("screen.universal_translator.blocked_keywords_hint"));

        this.targetLanguageButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE;
        }).dimensions(left, layout.targetY, layout.buttonWidth, 20).build());
        this.outgoingButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateOutgoing = !translateOutgoing;
            refreshLabels();
        }).dimensions(layout.right, layout.targetY, layout.buttonWidth, 20).build());
        this.endpoint = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, layout.endpointY, layout.buttonWidth, 20,
                new TranslatableText("screen.universal_translator.endpoint")));
        this.endpoint.setMaxLength(512);
        this.endpoint.setText(endpointValue);
        this.outgoingTargetLanguageButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE;
        }).dimensions(layout.right, layout.endpointY, layout.buttonWidth, 20).build());

        addDrawableChild(ButtonWidget.builder(new TranslatableText("screen.universal_translator.save"), button -> saveAndApply())
                .dimensions(left, layout.saveY, layout.buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(new TranslatableText("gui.cancel"), button -> onClose())
                .dimensions(layout.right, layout.saveY, layout.buttonWidth, 20).build());
        refreshLabels();
    }

    private void refreshLabels() {
        uiStyleButton.setMessage(new TranslatableText("screen.universal_translator.option.ui_style",
                tr(animatedUi ? "value.universal_translator.ui_animated"
                        : "value.universal_translator.ui_classic")));
        enabledButton.setMessage(new TranslatableText("screen.universal_translator.option.automatic", onOff(enabled)));
        chatButton.setMessage(new TranslatableText("screen.universal_translator.option.chat", onOff(translateChat)));
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
                TargetLanguage.displayName(targetLanguage)));
        outgoingTargetLanguageButton.setMessage(new TranslatableText(
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
            if (targetLanguage.trim().isEmpty()) {
                throw new IllegalArgumentException(tr("error.universal_translator.target_required"));
            }
            if (translateOutgoing && outgoingTargetLanguage.trim().isEmpty()) {
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
                    diskCache,
                    animatedUi);
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
        long now = System.nanoTime();
        float opening = 1.0F;
        if (animatedUi) {
            opening = SettingsUiAnimation.openProgress(animationStartedNanos, now);
            int center = this.width / 2;
            int half = SettingsUiAnimation.expandingHalfWidth(
                    layout.totalWidth / 2 + 12, opening);
            int panelLeft = center - half;
            int panelRight = center + half;
            int panelBottom = Math.min(this.height - 4, layout.saveY + 42);
            fill(matrices, 0, 0, this.width, this.height, 0x76070B10);
            fill(matrices, panelLeft - 2, 2, panelRight + 2, panelBottom + 2, 0x70101820);
            fill(matrices, panelLeft, 4, panelRight, panelBottom, 0xD41A232E);
            fill(matrices, panelLeft, 4, panelRight, 5, 0xCC55D6FF);
            fill(matrices, panelLeft, 32, panelRight, 33, 0x6655D6FF);
            int sweep = SettingsUiAnimation.sweepX(panelLeft, Math.max(panelLeft, panelRight - 26), now);
            fill(matrices, sweep, 32, Math.min(panelRight, sweep + 26), 34,
                    SettingsUiAnimation.pulseColor(now));
        }
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 18,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFF);
        int left = layout.left;
        drawTextWithShadow(matrices, this.textRenderer,
                new TranslatableText("screen.universal_translator.target_language_hint"),
                left, layout.targetY - 11, 0xA0A0A0);
        drawTextWithShadow(matrices, this.textRenderer,
                new TranslatableText("screen.universal_translator.endpoint_hint"),
                left, layout.endpointY - 11, 0xA0A0A0);
        drawTextWithShadow(matrices, this.textRenderer,
                new TranslatableText("screen.universal_translator.outgoing_target_hint"),
                layout.right, layout.endpointY - 11, 0xA0A0A0);
        String rawRuntimeStatus = FabricTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                UniversalTranslatorConfigScreen::tr);
        int belowSave = layout.saveY + 28;
        int messageY = belowSave <= this.height - 10 ? belowSave : layout.saveY - 14;
        if (!status.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, new LiteralText(status),
                    this.width / 2, messageY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, new LiteralText(runtimeStatus),
                    this.width / 2, messageY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFF5555 : 0x55FF55);
        } else if (layout.saveY - layout.endpointY >= 52) {
            int infoY = layout.endpointY + 28;
            drawCenteredText(matrices,
                    this.textRenderer,
                    new TranslatableText(isOffline()
                            ? "screen.universal_translator.info.offline"
                            : "screen.universal_translator.info.api"),
                    this.width / 2, infoY, 0xFFAA55);
            drawCenteredText(matrices, this.textRenderer,
                    new TranslatableText("screen.universal_translator.info.keybind"),
                    this.width / 2, infoY + 15, 0xA0A0A0);
        }
        super.render(matrices, mouseX, mouseY, delta);
        if (animatedUi) {
            int overlayAlpha = SettingsUiAnimation.openingOverlayAlpha(opening);
            if (overlayAlpha > 0) {
                fill(matrices, 0, 0, this.width, this.height, overlayAlpha << 24);
            }
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            renderSelection(matrices, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            selectFromList(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void renderSelection(MatrixStack matrices, int mouseX, int mouseY) {
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        fill(matrices, 0, 0, width, height, 0xB0080B10);
        fill(matrices, list.panelLeft() - 1, list.panelTop - 1,
                list.panelRight() + 1, list.panelBottom + 1, 0xFF55D6FF);
        fill(matrices, list.panelLeft(), list.panelTop,
                list.panelRight(), list.panelBottom, 0xF018202A);
        drawCenteredText(matrices, textRenderer, new TranslatableText(selectionTitleKey()),
                width / 2, list.panelTop + 9, 0xFFFFFF);
        for (int index = 0; index < values.length; index++) {
            int x = list.x(index);
            int y = list.y(index);
            boolean hovered = mouseX >= x && mouseX < x + list.buttonWidth
                    && mouseY >= y && mouseY < y + list.buttonHeight;
            boolean selected = values[index].equalsIgnoreCase(selectionValue());
            fill(matrices, x, y, x + list.buttonWidth, y + list.buttonHeight,
                    hovered ? 0xFF3B6178 : selected ? 0xFF28533D : 0xFF303844);
            drawCenteredText(matrices, textRenderer,
                    new LiteralText(SettingsSelectionList.displayName(openSelection, values[index])),
                    x + list.buttonWidth / 2, y + Math.max(1, (list.buttonHeight - 8) / 2),
                    selected ? 0x55FF88 : 0xFFFFFF);
        }
    }

    private void selectFromList(double mouseX, double mouseY) {
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        int selected = list.optionAt(mouseX, mouseY, values.length);
        if (selected >= 0) {
            if (openSelection == SettingsSelectionList.Kind.PROVIDER) provider = values[selected];
            else if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) targetLanguage = values[selected];
            else outgoingTargetLanguage = values[selected];
            openSelection = SettingsSelectionList.Kind.NONE;
            refreshLabels();
        } else if (!list.contains(mouseX, mouseY)) {
            openSelection = SettingsSelectionList.Kind.NONE;
        }
    }

    private String selectionValue() {
        if (openSelection == SettingsSelectionList.Kind.PROVIDER) return provider;
        if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) return targetLanguage;
        return outgoingTargetLanguage;
    }

    private String selectionTitleKey() {
        if (openSelection == SettingsSelectionList.Kind.PROVIDER) return "screen.universal_translator.selection.provider";
        if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) return "screen.universal_translator.selection.target_language";
        return "screen.universal_translator.selection.outgoing_language";
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
        int totalWidth = Math.max(180, Math.min(310, this.width - 20));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;
        int top = Math.max(20, Math.min(44, 20 + Math.max(0, this.height - 220) / 4));
        int rowStep = this.height >= 300 ? 26 : (this.height >= 260 ? 22 : 20);
        int targetY = top + rowStep * 7 + 2;
        int endpointY = targetY + (this.height >= 300 ? 32 : 28);
        int saveY = Math.max(endpointY + 22, this.height - 24);
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
