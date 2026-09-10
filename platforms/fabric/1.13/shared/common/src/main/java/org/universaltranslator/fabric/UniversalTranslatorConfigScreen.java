package org.universaltranslator.fabric;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.resource.language.I18n;

import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationProviderCatalog;
import org.universaltranslator.core.SettingsUiAnimation;
import org.universaltranslator.core.SettingsScreenLayout;
import org.universaltranslator.core.SettingsSelectionList;

final class UniversalTranslatorConfigScreen extends Screen {
    private enum Tab {
        GENERAL,
        SCOPES,
        ENGINE,
        OUTGOING
    }

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
    private static final int UI_STYLE = 20;
    private static final int TAB_GENERAL = 101;
    private static final int TAB_SCOPES = 102;
    private static final int TAB_ENGINE = 103;
    private static final int TAB_OUTGOING = 104;
    private static final int LLM_SETTINGS = 105;

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

    private Tab activeTab = Tab.GENERAL;
    private ActionButton tabGeneralButton;
    private ActionButton tabScopesButton;
    private ActionButton tabEngineButton;
    private ActionButton tabOutgoingButton;

    private ActionButton enabledButton;
    private ActionButton targetLanguageButton;
    private ActionButton displayButton;
    private ActionButton colorButton;
    private ActionButton cacheButton;
    private ActionButton uiStyleButton;
    private ActionButton diagnosticsButton;

    private ActionButton chatButton;
    private ActionButton otherButton;
    private ActionButton vanillaButton;
    private ActionButton playerNamesButton;
    private ActionButton mixedTextButton;
    private TextFieldWidget blockedKeywords;

    private ActionButton providerButton;
    private ActionButton modelButton;
    private ActionButton downloadButton;
    private ActionButton fallbackButton;
    private ActionButton llmSettingsButton;
    private TextFieldWidget endpoint;

    private ActionButton outgoingButton;
    private ActionButton outgoingTargetButton;

    private ActionButton saveButton;
    private ActionButton cancelButton;

    private TextRenderer renderer;
    private String status = "";
    private long animationStartedNanos = System.nanoTime();
    private SettingsSelectionList.Kind openSelection = SettingsSelectionList.Kind.NONE;

    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
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
    public void init() {
        if (targetLanguage == null) {
            targetLanguage = TargetLanguage.canonicalize(original.targetLanguage);
        }
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getText();
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getText();
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }
        buttons.clear();
        renderer = OrnitheClientAccess.textRenderer();
        SettingsScreenLayout.Geometry layout = SettingsScreenLayout.calculate(width, height);
        int left = layout.left();
        int right = layout.right();
        int buttonWidth = layout.buttonWidth();
        int totalWidth = layout.totalWidth();

        // 4 Navigation Tabs
        tabGeneralButton = new ActionButton(TAB_GENERAL, layout.tabX(0), layout.tabY(), layout.tabWidth(), 20,
                tr("category.universal_translator.general"));
        tabScopesButton = new ActionButton(TAB_SCOPES, layout.tabX(1), layout.tabY(), layout.tabWidth(), 20,
                tr("category.universal_translator.scopes"));
        tabEngineButton = new ActionButton(TAB_ENGINE, layout.tabX(2), layout.tabY(), layout.tabWidth(), 20,
                tr("category.universal_translator.engine"));
        tabOutgoingButton = new ActionButton(TAB_OUTGOING, layout.tabX(3), layout.tabY(), layout.tabWidth(), 20,
                tr("category.universal_translator.outgoing"));

        addButton(tabGeneralButton);
        addButton(tabScopesButton);
        addButton(tabEngineButton);
        addButton(tabOutgoingButton);

        // Tab 1: General (常規)
        enabledButton = new ActionButton(ENABLED, left, layout.contentRow(0), buttonWidth, 20, "");
        targetLanguageButton = new ActionButton(TARGET_LANGUAGE, right, layout.contentRow(0), buttonWidth, 20, "");
        displayButton = new ActionButton(DISPLAY, left, layout.contentRow(1), buttonWidth, 20, "");
        colorButton = new ActionButton(COLOR, right, layout.contentRow(1), buttonWidth, 20, "");
        cacheButton = new ActionButton(CACHE, left, layout.contentRow(2), buttonWidth, 20, "");
        uiStyleButton = new ActionButton(UI_STYLE, right, layout.contentRow(2), buttonWidth, 20, "");
        diagnosticsButton = new ActionButton(DIAGNOSTICS, left, layout.contentRow(3), totalWidth, 20,
                tr("screen.universal_translator.diagnostics.title"));

        addButton(enabledButton);
        addButton(targetLanguageButton);
        addButton(displayButton);
        addButton(colorButton);
        addButton(cacheButton);
        addButton(uiStyleButton);
        addButton(diagnosticsButton);

        // Tab 2: Scopes (範圍)
        chatButton = new ActionButton(CHAT, left, layout.contentRow(0), buttonWidth, 20, "");
        otherButton = new ActionButton(OTHER, right, layout.contentRow(0), buttonWidth, 20, "");
        vanillaButton = new ActionButton(VANILLA, left, layout.contentRow(1), buttonWidth, 20, "");
        playerNamesButton = new ActionButton(PLAYER_NAMES, right, layout.contentRow(1), buttonWidth, 20, "");
        mixedTextButton = new ActionButton(MIXED_TEXT, left, layout.contentRow(2), totalWidth, 20, "");
        blockedKeywords = new TextFieldWidget(22, renderer, left, layout.contentRow(3), totalWidth, 20);
        blockedKeywords.setMaxLength(4096);
        blockedKeywords.setText(blockedKeywordsValue);

        addButton(chatButton);
        addButton(otherButton);
        addButton(vanillaButton);
        addButton(playerNamesButton);
        addButton(mixedTextButton);

        // Tab 3: Engine (引擎)
        providerButton = new ActionButton(PROVIDER, left, layout.contentRow(0), totalWidth, 20, "");
        modelButton = new ActionButton(MODEL, left, layout.contentRow(1), buttonWidth, 20, "");
        downloadButton = new ActionButton(DOWNLOAD, right, layout.contentRow(1), buttonWidth, 20, "");
        fallbackButton = new ActionButton(FALLBACK, left, layout.contentRow(2), totalWidth, 20, "");
        llmSettingsButton = new ActionButton(LLM_SETTINGS, left, layout.contentRow(1), buttonWidth, 20,
                tr("screen.universal_translator.option.llm_settings"));
        endpoint = new TextFieldWidget(21, renderer, left, layout.contentRow(isLlm() ? 2 : 1), totalWidth, 20);
        endpoint.setMaxLength(512);
        endpoint.setText(endpointValue);

        addButton(providerButton);
        addButton(modelButton);
        addButton(downloadButton);
        addButton(fallbackButton);
        addButton(llmSettingsButton);

        // Tab 4: Outgoing (傳送)
        outgoingButton = new ActionButton(OUTGOING, left, layout.contentRow(0), buttonWidth, 20, "");
        outgoingTargetButton = new ActionButton(OUTGOING_TARGET_LANGUAGE, right, layout.contentRow(0), buttonWidth, 20, "");

        addButton(outgoingButton);
        addButton(outgoingTargetButton);

        // Bottom Bar
        saveButton = new ActionButton(SAVE, left, layout.saveY(), buttonWidth, 20,
                tr("screen.universal_translator.save"));
        cancelButton = new ActionButton(CANCEL, right, layout.saveY(), buttonWidth, 20,
                tr("gui.cancel"));

        addButton(saveButton);
        addButton(cancelButton);

        updateTabVisibility();
        refreshLabels();
    }

    private void updateTabVisibility() {
        tabGeneralButton.active = activeTab != Tab.GENERAL;
        tabScopesButton.active = activeTab != Tab.SCOPES;
        tabEngineButton.active = activeTab != Tab.ENGINE;
        tabOutgoingButton.active = activeTab != Tab.OUTGOING;

        // General
        boolean isGen = activeTab == Tab.GENERAL;
        enabledButton.visible = isGen;
        targetLanguageButton.visible = isGen;
        displayButton.visible = isGen;
        colorButton.visible = isGen;
        cacheButton.visible = isGen;
        uiStyleButton.visible = isGen;
        diagnosticsButton.visible = isGen;

        // Scopes
        boolean isScope = activeTab == Tab.SCOPES;
        chatButton.visible = isScope;
        otherButton.visible = isScope;
        vanillaButton.visible = isScope;
        playerNamesButton.visible = isScope;
        mixedTextButton.visible = isScope;
        blockedKeywords.setVisible(isScope);

        // Engine
        boolean isEng = activeTab == Tab.ENGINE;
        providerButton.visible = isEng;
        boolean offline = isOffline();
        boolean llm = isLlm();
        modelButton.visible = isEng && offline;
        downloadButton.visible = isEng && offline;
        fallbackButton.visible = isEng && offline;
        llmSettingsButton.visible = isEng && llm;
        endpoint.setVisible(isEng && !offline);

        // Outgoing
        boolean isOut = activeTab == Tab.OUTGOING;
        outgoingButton.visible = isOut;
        outgoingTargetButton.visible = isOut;
        outgoingTargetButton.active = translateOutgoing;
    }

    void buttonClicked(ButtonWidget button) {
        if (button.id == TAB_GENERAL) {
            activeTab = Tab.GENERAL;
            updateTabVisibility();
            return;
        } else if (button.id == TAB_SCOPES) {
            activeTab = Tab.SCOPES;
            updateTabVisibility();
            return;
        } else if (button.id == TAB_ENGINE) {
            activeTab = Tab.ENGINE;
            updateTabVisibility();
            return;
        } else if (button.id == TAB_OUTGOING) {
            activeTab = Tab.OUTGOING;
            updateTabVisibility();
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
            openSelection = SettingsSelectionList.Kind.PROVIDER;
        } else if (button.id == DISPLAY) {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
        } else if (button.id == MIXED_TEXT) {
            translateEnglishOnly = !translateEnglishOnly;
        } else if (button.id == COLOR) {
            translatedTextColor = translatedTextColor.next();
        } else if (button.id == DOWNLOAD) {
            offlineAutoDownload = !offlineAutoDownload;
        } else if (button.id == LLM_SETTINGS) {
            OrnitheClientAccess.openScreen(new UniversalTranslatorLlmConfigScreen(
                    this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
            return;
        } else if (button.id == FALLBACK) {
            apiFallback = !apiFallback;
        } else if (button.id == OUTGOING) {
            translateOutgoing = !translateOutgoing;
        } else if (button.id == PLAYER_NAMES) {
            translatePlayerNames = !translatePlayerNames;
        } else if (button.id == UI_STYLE) {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
        } else if (button.id == MODEL) {
            offlineModel = offlineModel.next();
        } else if (button.id == DIAGNOSTICS) {
            OrnitheClientAccess.openScreen(new UniversalTranslatorDiagnosticsScreen(this));
            return;
        } else if (button.id == TARGET_LANGUAGE) {
            openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE;
        } else if (button.id == OUTGOING_TARGET_LANGUAGE) {
            openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE;
        } else if (button.id == SAVE) {
            saveAndApply();
            return;
        } else if (button.id == CANCEL) {
            OrnitheClientAccess.openScreen(parent);
            return;
        }
        refreshLabels();
    }

    private void refreshLabels() {
        if (uiStyleButton != null) {
            uiStyleButton.message = tr("screen.universal_translator.option.ui_style",
                    tr(animatedUi ? "value.universal_translator.ui_animated"
                            : "value.universal_translator.ui_classic"));
        }
        if (enabledButton != null) {
            enabledButton.message = tr("screen.universal_translator.option.automatic", onOff(enabled));
        }
        if (targetLanguageButton != null) {
            targetLanguageButton.message = tr("screen.universal_translator.option.target_preset",
                    TargetLanguage.displayName(targetLanguage));
        }
        if (displayButton != null) {
            displayButton.message = tr("screen.universal_translator.option.display",
                    tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                            ? "value.universal_translator.display_bilingual"
                            : "value.universal_translator.display_translated"));
        }
        if (colorButton != null) {
            colorButton.message = tr("screen.universal_translator.option.color", colorLabel(translatedTextColor));
        }
        if (cacheButton != null) {
            cacheButton.message = tr("screen.universal_translator.option.cache", onOff(diskCache));
        }
        if (chatButton != null) {
            chatButton.message = tr("screen.universal_translator.option.chat", onOff(translateChat));
        }
        if (otherButton != null) {
            otherButton.message = tr("screen.universal_translator.option.other", onOff(translateOther));
        }
        if (vanillaButton != null) {
            vanillaButton.message = tr("screen.universal_translator.option.vanilla", onOff(translateVanilla));
        }
        if (playerNamesButton != null) {
            playerNamesButton.message = tr(
                    "screen.universal_translator.option.player_names", onOff(translatePlayerNames));
        }
        if (mixedTextButton != null) {
            mixedTextButton.message = tr("screen.universal_translator.option.mixed", onOff(translateEnglishOnly));
        }
        if (providerButton != null) {
            providerButton.message = tr("screen.universal_translator.option.provider", providerLabel());
        }
        if (modelButton != null) {
            modelButton.message = tr("screen.universal_translator.option.model", offlineModel.displayName());
            modelButton.active = isOffline();
        }
        if (downloadButton != null) {
            downloadButton.message = tr("screen.universal_translator.option.download", onOff(offlineAutoDownload));
            downloadButton.active = isOffline();
        }
        if (fallbackButton != null) {
            fallbackButton.message = tr("screen.universal_translator.option.fallback", onOff(apiFallback));
            fallbackButton.active = isOffline();
        }
        if (outgoingButton != null) {
            outgoingButton.message = tr("screen.universal_translator.option.outgoing", onOff(translateOutgoing));
        }
        if (outgoingTargetButton != null) {
            outgoingTargetButton.message = tr(
                    "screen.universal_translator.option.outgoing_target",
                    TargetLanguage.displayName(outgoingTargetLanguage));
            outgoingTargetButton.active = translateOutgoing;
        }
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
            OrnitheClientAccess.openScreen(parent);
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
    public void tick() {
        if (activeTab == Tab.ENGINE && !isOffline() && endpoint != null) {
            endpoint.tick();
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null) {
            blockedKeywords.tick();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeTab == Tab.ENGINE && !isOffline() && endpoint != null && endpoint.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null && blockedKeywords.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (activeTab == Tab.ENGINE && !isOffline() && endpoint != null && endpoint.charTyped(chr, modifiers)) {
            return true;
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null && blockedKeywords.charTyped(chr, modifiers)) {
            return true;
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            selectFromList(mouseX, mouseY);
            return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, mouseButton);
        if (activeTab == Tab.ENGINE && !isOffline() && endpoint != null) {
            endpoint.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null) {
            blockedKeywords.mouseClicked(mouseX, mouseY, mouseButton);
        }
        return handled;
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        renderBackground();
        SettingsScreenLayout.Geometry layout = SettingsScreenLayout.calculate(width, height);
        long now = System.nanoTime();
        float opening = 1.0F;
        if (animatedUi) {
            opening = SettingsUiAnimation.openProgress(animationStartedNanos, now);
            int center = width / 2;
            int half = SettingsUiAnimation.expandingHalfWidth(
                    layout.totalWidth() / 2 + 12, opening);
            int panelLeft = center - half;
            int panelRight = center + half;
            int panelBottom = Math.min(height - 4, layout.saveY() + 42);
            fill(0, 0, width, height, 0x76070B10);
            fill(panelLeft - 2, 2, panelRight + 2, panelBottom + 2, 0x70101820);
            fill(panelLeft, 4, panelRight, panelBottom, 0xD41A232E);
            fill(panelLeft, 4, panelRight, 5, 0xCC55D6FF);
            fill(panelLeft, 32, panelRight, 33, 0x6655D6FF);
            int sweep = SettingsUiAnimation.sweepX(
                    panelLeft, Math.max(panelLeft, panelRight - 26), now);
            fill(sweep, 32, Math.min(panelRight, sweep + 26), 34,
                    SettingsUiAnimation.pulseColor(now));

            // Animated Tab underline
            int tabX = layout.tabX(activeTab.ordinal());
            int tabW = layout.tabWidth();
            fill(tabX, layout.tabY() + 20, tabX + tabW, layout.tabY() + 22, 0xFF55D6FF);
        } else {
            // Static active tab indicator
            int tabX = layout.tabX(activeTab.ordinal());
            int tabW = layout.tabWidth();
            fill(tabX, layout.tabY() + 20, tabX + tabW, layout.tabY() + 22, 0xFF55D6FF);
        }

        drawCenteredString(renderer, tr("screen.universal_translator.settings.title"),
                width / 2, 10,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFFFF);

        super.render(mouseX, mouseY, partialTicks);

        // Tab-specific text and fields
        if (activeTab == Tab.SCOPES) {
            OrnitheClientAccess.renderTextField(blockedKeywords, mouseX, mouseY, partialTicks);
            if (blockedKeywords.getText().isEmpty()) {
                drawString(renderer,
                        tr("screen.universal_translator.blocked_keywords_hint"),
                        layout.left() + 4, layout.contentRow(3) + 6, 0x70A0A0A0);
            }
        } else if (activeTab == Tab.ENGINE) {
            if (!isOffline()) {
                OrnitheClientAccess.renderTextField(endpoint, mouseX, mouseY, partialTicks);
                drawString(renderer,
                        tr("screen.universal_translator.endpoint"),
                        layout.left(), layout.contentRow(isLlm() ? 2 : 1) - 11, 0xFFAAAAAA);
            }
            int hintY = layout.contentRow(isOffline() ? 3 : (isLlm() ? 3 : 2)) + 6;
            if (hintY < layout.saveY() - 12) {
                drawCenteredString(renderer,
                        tr(isOffline() ? "screen.universal_translator.info.offline" : "screen.universal_translator.info.api"),
                        width / 2, hintY, 0xFFFFAA55);
            }
        } else if (activeTab == Tab.OUTGOING) {
            int tipY = layout.contentRow(2);
            drawCenteredString(renderer, tr("screen.universal_translator.outgoing_tip"), width / 2, tipY, 0xFFAAAAAA);
        }

        // Status & feedback
        String rawRuntimeStatus = FabricTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                UniversalTranslatorConfigScreen::tr);
        int messageY = layout.saveY() + 24;
        if (messageY > height - 10) {
            messageY = height - 10;
        }
        if (!status.isEmpty()) {
            drawCenteredString(renderer, status, width / 2, messageY, 0xFFFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredString(renderer, runtimeStatus, width / 2, messageY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFFFF5555 : 0xFF55FF55);
        }

        if (animatedUi) {
            int overlayAlpha = SettingsUiAnimation.openingOverlayAlpha(opening);
            if (overlayAlpha > 0) {
                fill(0, 0, width, height, overlayAlpha << 24);
            }
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            renderSelection(mouseX, mouseY);
        }
    }

    private void renderSelection(int mouseX, int mouseY) {
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        fill(0, 0, width, height, 0xB0080B10);
        fill(list.panelLeft() - 1, list.panelTop - 1,
                list.panelRight() + 1, list.panelBottom + 1, 0xFF55D6FF);
        fill(list.panelLeft(), list.panelTop,
                list.panelRight(), list.panelBottom, 0xF018202A);
        drawCenteredString(renderer, tr(selectionTitleKey()),
                width / 2, list.panelTop + 9, 0xFFFFFFFF);
        for (int index = 0; index < values.length; index++) {
            int x = list.x(index);
            int y = list.y(index);
            boolean hovered = mouseX >= x && mouseX < x + list.buttonWidth
                    && mouseY >= y && mouseY < y + list.buttonHeight;
            boolean selected = values[index].equalsIgnoreCase(selectionValue());
            fill(x, y, x + list.buttonWidth, y + list.buttonHeight,
                    hovered ? 0xFF3B6178 : selected ? 0xFF28533D : 0xFF303844);
            drawCenteredString(renderer,
                    SettingsSelectionList.displayName(openSelection, values[index]),
                    x + list.buttonWidth / 2, y + Math.max(1, (list.buttonHeight - 8) / 2),
                    selected ? 0xFF55FF88 : 0xFFFFFFFF);
        }
    }

    private void selectFromList(double mouseX, double mouseY) {
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        int selected = list.optionAt(mouseX, mouseY, values.length);
        if (selected >= 0) {
            if (openSelection == SettingsSelectionList.Kind.PROVIDER) {
                provider = values[selected];
                loadLlmSettings(provider);
                updateTabVisibility();
            } else if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) {
                targetLanguage = values[selected];
            } else {
                outgoingTargetLanguage = values[selected];
            }
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
    public boolean shouldPauseGame() {
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
        return I18n.translate(key, arguments);
    }
}
