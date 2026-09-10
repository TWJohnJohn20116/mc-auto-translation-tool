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
import org.universaltranslator.core.SettingsScreenLayout;
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

    private enum Tab {
        GENERAL,
        SCOPES,
        ENGINE,
        OUTGOING
    }
    private Tab activeTab = Tab.GENERAL;
    private ButtonWidget tabGeneralButton;
    private ButtonWidget tabScopesButton;
    private ButtonWidget tabEngineButton;
    private ButtonWidget tabOutgoingButton;
    private ButtonWidget llmConfigButton;

    private TextFieldWidget endpoint;
    private TextFieldWidget blockedKeywords;
    private ButtonWidget enabledButton;
    private ButtonWidget uiStyleButton;
    private ButtonWidget chatButton;
    private ButtonWidget otherButton;
    private ButtonWidget vanillaButton;
    private ButtonWidget playerNamesButton;
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
        this.llmEndpoint = config.editorEndpoint(config.provider);
        this.llmApiKey = config.editorApiKey(config.provider);
        this.llmModel = config.editorModel(config.provider);
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

        // Navigation Tabs (positioned right below header at tabY)
        this.tabGeneralButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            activeTab = Tab.GENERAL;
            updateTabVisibility();
        }).dimensions(layout.tabX(0), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabScopesButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            activeTab = Tab.SCOPES;
            updateTabVisibility();
        }).dimensions(layout.tabX(1), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabEngineButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            activeTab = Tab.ENGINE;
            updateTabVisibility();
        }).dimensions(layout.tabX(2), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabOutgoingButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            activeTab = Tab.OUTGOING;
            updateTabVisibility();
        }).dimensions(layout.tabX(3), layout.tabY(), layout.tabWidth(), 20).build());

        // --- Tab 1: General (常規) ---
        this.enabledButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            enabled = !enabled;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.targetLanguageButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE;
        }).dimensions(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.displayButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
            refreshLabels();
        }).dimensions(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.colorButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translatedTextColor = translatedTextColor.next();
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.cacheButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).dimensions(left, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.uiStyleButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.diagnosticsButton = addDrawableChild(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.diagnostics.title"), button -> {
            if (client != null) {
                client.setScreen(new UniversalTranslatorDiagnosticsScreen(this));
            }
        }).dimensions(left, layout.contentRow(3), layout.totalWidth, 20).build());

        // --- Tab 2: Scopes (範圍) ---
        this.chatButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.otherButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.vanillaButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateVanilla = !translateVanilla;
            refreshLabels();
        }).dimensions(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.playerNamesButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translatePlayerNames = !translatePlayerNames;
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.mixedTextButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateEnglishOnly = !translateEnglishOnly;
            refreshLabels();
        }).dimensions(left, layout.contentRow(2), layout.totalWidth, 20).build());

        this.blockedKeywords = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, layout.contentRow(3), layout.totalWidth, 20,
                new TranslatableText("screen.universal_translator.blocked_keywords")));
        this.blockedKeywords.setMaxLength(4096);
        this.blockedKeywords.setText(blockedKeywordsValue);
        this.blockedKeywords.setSuggestion(tr("screen.universal_translator.blocked_keywords_hint"));

        // --- Tab 3: Engine (引擎) ---
        this.providerButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            openSelection = SettingsSelectionList.Kind.PROVIDER;
        }).dimensions(left, layout.contentRow(0), layout.totalWidth, 20).build());

        this.modelButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            offlineModel = offlineModel.next();
            refreshLabels();
        }).dimensions(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.downloadButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            offlineAutoDownload = !offlineAutoDownload;
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.endpoint = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, layout.contentRow(1), layout.totalWidth, 20,
                new TranslatableText("screen.universal_translator.endpoint")));
        this.endpoint.setMaxLength(512);
        this.endpoint.setText(endpointValue);

        this.fallbackButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).dimensions(left, layout.contentRow(2), layout.totalWidth, 20).build());

        this.llmConfigButton = addDrawableChild(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.option.llm_settings"), button -> {
            if (this.client != null) {
                this.client.setScreen(new UniversalTranslatorLlmConfigScreen(
                        this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
            }
        }).dimensions(left, layout.contentRow(2), layout.totalWidth, 20).build());

        // --- Tab 4: Outgoing (傳送) ---
        this.outgoingButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            translateOutgoing = !translateOutgoing;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.totalWidth, 20).build());

        this.outgoingTargetLanguageButton = addDrawableChild(ButtonWidget.builder(new LiteralText(""), button -> {
            openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE;
        }).dimensions(left, layout.contentRow(1), layout.totalWidth, 20).build());

        // --- Bottom Action Row ---
        addDrawableChild(ButtonWidget.builder(new TranslatableText("screen.universal_translator.save"), button -> saveAndApply())
                .dimensions(left, layout.saveY, layout.buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(new TranslatableText("gui.cancel"), button -> onClose())
                .dimensions(layout.right, layout.saveY, layout.buttonWidth, 20).build());

        refreshLabels();
        updateTabVisibility();
    }

    private void refreshLabels() {
        uiStyleButton.setMessage(new TranslatableText("screen.universal_translator.option.ui_style",
                tr(animatedUi ? "value.universal_translator.ui_animated"
                        : "value.universal_translator.ui_classic")));
        enabledButton.setMessage(new TranslatableText("screen.universal_translator.option.automatic", onOff(enabled)));
        chatButton.setMessage(new TranslatableText("screen.universal_translator.option.chat", onOff(translateChat)));
        otherButton.setMessage(new TranslatableText("screen.universal_translator.option.other", onOff(translateOther)));
        vanillaButton.setMessage(new TranslatableText("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        playerNamesButton.setMessage(new TranslatableText(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames)));
        cacheButton.setMessage(new TranslatableText("screen.universal_translator.option.cache", onOff(diskCache)));
        providerButton.setMessage(new TranslatableText("screen.universal_translator.option.provider", providerLabel()));
        displayButton.setMessage(new TranslatableText("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        mixedTextButton.setMessage(new TranslatableText("screen.universal_translator.option.mixed", onOff(translateEnglishOnly)));
        colorButton.setMessage(new TranslatableText("screen.universal_translator.option.color", colorLabel(translatedTextColor)));
        downloadButton.setMessage(new TranslatableText("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        modelButton.setMessage(new TranslatableText("screen.universal_translator.option.model", offlineModel.displayName()));
        fallbackButton.setMessage(new TranslatableText("screen.universal_translator.option.fallback", onOff(apiFallback)));
        outgoingButton.setMessage(new TranslatableText("screen.universal_translator.option.outgoing", onOff(translateOutgoing)));
        targetLanguageButton.setMessage(new TranslatableText("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage)));
        outgoingTargetLanguageButton.setMessage(new TranslatableText(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage)));
        outgoingTargetLanguageButton.active = translateOutgoing;
        refreshTabButtons();
    }

    private void updateTabVisibility() {
        boolean isGeneral = activeTab == Tab.GENERAL;
        boolean isScopes = activeTab == Tab.SCOPES;
        boolean isEngine = activeTab == Tab.ENGINE;
        boolean isOutgoing = activeTab == Tab.OUTGOING;

        // Tab 1: General
        enabledButton.visible = isGeneral;
        targetLanguageButton.visible = isGeneral;
        displayButton.visible = isGeneral;
        colorButton.visible = isGeneral;
        cacheButton.visible = isGeneral;
        uiStyleButton.visible = isGeneral;
        diagnosticsButton.visible = isGeneral;

        // Tab 2: Scopes
        chatButton.visible = isScopes;
        otherButton.visible = isScopes;
        vanillaButton.visible = isScopes;
        playerNamesButton.visible = isScopes;
        mixedTextButton.visible = isScopes;
        blockedKeywords.visible = isScopes;

        // Tab 3: Engine
        providerButton.visible = isEngine;
        boolean offline = isOffline();
        boolean llm = isLlm();
        modelButton.visible = isEngine && offline;
        downloadButton.visible = isEngine && offline;
        fallbackButton.visible = isEngine && offline;
        endpoint.visible = isEngine && !offline;
        llmConfigButton.visible = isEngine && llm;

        // Tab 4: Outgoing
        outgoingButton.visible = isOutgoing;
        outgoingTargetLanguageButton.visible = isOutgoing;

        refreshTabButtons();
    }

    private void refreshTabButtons() {
        if (tabGeneralButton == null) return;
        tabGeneralButton.setMessage(tabTitle("screen.universal_translator.tab.general", activeTab == Tab.GENERAL));
        tabScopesButton.setMessage(tabTitle("screen.universal_translator.tab.scopes", activeTab == Tab.SCOPES));
        tabEngineButton.setMessage(tabTitle("screen.universal_translator.tab.engine", activeTab == Tab.ENGINE));
        tabOutgoingButton.setMessage(tabTitle("screen.universal_translator.tab.outgoing", activeTab == Tab.OUTGOING));
    }

    private Text tabTitle(String key, boolean active) {
        String label = tr(key);
        return new LiteralText(active ? "§b§l[ " + label + " ]" : "§7" + label);
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

            // Glowing underline for active tab
            int tabIndex = activeTab.ordinal();
            int tabX = layout.tabX(tabIndex);
            fill(matrices, tabX, layout.tabY() + 20, tabX + layout.tabWidth(), layout.tabY() + 22, 0xFF55D6FF);
        }
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 16,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFFFF);
        String rawRuntimeStatus = FabricTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                UniversalTranslatorConfigScreen::tr);
        int belowSave = layout.saveY + 24;
        int messageY = belowSave <= this.height - 10 ? belowSave : SettingsScreenLayout.COMPACT_STATUS_Y;
        if (!status.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, new LiteralText(status),
                    this.width / 2, messageY, 0xFFFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, new LiteralText(runtimeStatus),
                    this.width / 2, messageY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFFFF5555 : 0xFF55FF55);
        } else {
            if (activeTab == Tab.OUTGOING) {
                drawCenteredText(matrices, this.textRenderer,
                        new TranslatableText("screen.universal_translator.info.outgoing_hint"),
                        this.width / 2, layout.contentRow(2) + 6, 0xFFA0A0A0);
            } else if (activeTab == Tab.GENERAL) {
                drawCenteredText(matrices, this.textRenderer,
                        new TranslatableText("screen.universal_translator.info.keybind"),
                        this.width / 2, layout.contentRow(3) + 24, 0xFFA0A0A0);
            } else if (activeTab == Tab.ENGINE) {
                drawCenteredText(matrices, this.textRenderer,
                        new TranslatableText(isOffline()
                                ? "screen.universal_translator.info.offline"
                                : "screen.universal_translator.info.api"),
                        this.width / 2, layout.contentRow(3) + 6, 0xFFFFAA55);
            }
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
        if (openSelection != SettingsSelectionList.Kind.NONE
                && selectFromList(mouseX, mouseY)) {
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
        drawCenteredText(matrices, textRenderer,
                new TranslatableText(selectionTitleKey()), width / 2, list.panelTop + 9, 0xFFFFFFFF);
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
                    selected ? 0xFF55FF88 : 0xFFFFFFFF);
        }
    }

    private boolean selectFromList(double mouseX, double mouseY) {
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        int selected = list.optionAt(mouseX, mouseY, values.length);
        if (selected >= 0) {
            if (openSelection == SettingsSelectionList.Kind.PROVIDER) {
                provider = values[selected];
                loadLlmSettings(provider);
            } else if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) {
                targetLanguage = values[selected];
            } else {
                outgoingTargetLanguage = values[selected];
            }
            openSelection = SettingsSelectionList.Kind.NONE;
            refreshLabels();
            return true;
        } else if (!list.contains(mouseX, mouseY)) {
            openSelection = SettingsSelectionList.Kind.NONE;
            return false;
        }
        return true;
    }

    private String selectionValue() {
        if (openSelection == SettingsSelectionList.Kind.PROVIDER) return provider;
        if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) return targetLanguage;
        return outgoingTargetLanguage;
    }

    private String selectionTitleKey() {
        if (openSelection == SettingsSelectionList.Kind.PROVIDER) {
            return "screen.universal_translator.selection.provider";
        }
        if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) {
            return "screen.universal_translator.selection.target_language";
        }
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

    private void loadLlmSettings(String selectedProvider) {
        if (!TranslationProviderCatalog.usesLlmEditor(selectedProvider)) {
            return;
        }
        this.llmEndpoint = original.editorEndpoint(selectedProvider);
        this.llmApiKey = original.editorApiKey(selectedProvider);
        this.llmModel = original.editorModel(selectedProvider);
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
        SettingsScreenLayout.Geometry geometry = SettingsScreenLayout.calculate(this.width, this.height);
        return new Layout(geometry.left(), geometry.right(), geometry.totalWidth(), geometry.buttonWidth(),
                geometry.top(), geometry.rowStep(), geometry.targetY(), geometry.endpointY(), geometry.saveY(),
                geometry.tabY(), geometry.tabWidth(), geometry.tabGap(), geometry.contentTop());
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
        private final int tabY;
        private final int tabWidth;
        private final int tabGap;
        private final int contentTop;

        private Layout(int left, int right, int totalWidth, int buttonWidth,
                       int top, int rowStep, int targetY, int endpointY, int saveY,
                       int tabY, int tabWidth, int tabGap, int contentTop) {
            this.left = left;
            this.right = right;
            this.totalWidth = totalWidth;
            this.buttonWidth = buttonWidth;
            this.top = top;
            this.rowStep = rowStep;
            this.targetY = targetY;
            this.endpointY = endpointY;
            this.saveY = saveY;
            this.tabY = tabY;
            this.tabWidth = tabWidth;
            this.tabGap = tabGap;
            this.contentTop = contentTop;
        }

        private int row(int index) {
            return top + rowStep * index;
        }

        private int tabY() {
            return tabY;
        }

        private int tabWidth() {
            return tabWidth;
        }

        private int tabX(int index) {
            return left + index * (tabWidth + tabGap);
        }

        private int contentRow(int index) {
            return contentTop + rowStep * index;
        }
    }
}
