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
import org.universaltranslator.core.SettingsUiAnimation;
import org.universaltranslator.core.SettingsScreenLayout;
import org.universaltranslator.core.SettingsSelectionList;

/** Dependency-free settings UI shared by Forge 1.8.9 and 1.12.2. */
final class LegacyConfigScreen extends GuiScreen {
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

    private final GuiScreen parent;
    private final LegacyConfig original;
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
    private GuiButton tabGeneralButton;
    private GuiButton tabScopesButton;
    private GuiButton tabEngineButton;
    private GuiButton tabOutgoingButton;

    private GuiButton enabledButton;
    private GuiButton targetLanguageButton;
    private GuiButton displayButton;
    private GuiButton colorButton;
    private GuiButton cacheButton;
    private GuiButton uiStyleButton;
    private GuiButton diagnosticsButton;

    private GuiButton chatButton;
    private GuiButton otherButton;
    private GuiButton vanillaButton;
    private GuiButton playerNamesButton;
    private GuiButton mixedTextButton;
    private GuiTextField blockedKeywords;

    private GuiButton providerButton;
    private GuiButton modelButton;
    private GuiButton downloadButton;
    private GuiButton fallbackButton;
    private GuiButton llmSettingsButton;
    private GuiTextField endpoint;

    private GuiButton outgoingButton;
    private GuiButton outgoingTargetButton;

    private GuiButton saveButton;
    private GuiButton cancelButton;

    private FontRenderer renderer;
    private String status = "";
    private long animationStartedNanos = System.nanoTime();
    private SettingsSelectionList.Kind openSelection = SettingsSelectionList.Kind.NONE;

    LegacyConfigScreen(GuiScreen parent, LegacyConfig config) {
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
        SettingsScreenLayout.Geometry layout = SettingsScreenLayout.calculate(width, height);
        int left = layout.left();
        int right = layout.right();
        int buttonWidth = layout.buttonWidth();
        int totalWidth = layout.totalWidth();

        // 4 Navigation Tabs
        tabGeneralButton = new GuiButton(TAB_GENERAL, layout.tabX(0), layout.tabY(), layout.tabWidth(), 20,
                tr("category.universal_translator.general"));
        tabScopesButton = new GuiButton(TAB_SCOPES, layout.tabX(1), layout.tabY(), layout.tabWidth(), 20,
                tr("category.universal_translator.scopes"));
        tabEngineButton = new GuiButton(TAB_ENGINE, layout.tabX(2), layout.tabY(), layout.tabWidth(), 20,
                tr("category.universal_translator.engine"));
        tabOutgoingButton = new GuiButton(TAB_OUTGOING, layout.tabX(3), layout.tabY(), layout.tabWidth(), 20,
                tr("category.universal_translator.outgoing"));

        buttonList.add(tabGeneralButton);
        buttonList.add(tabScopesButton);
        buttonList.add(tabEngineButton);
        buttonList.add(tabOutgoingButton);

        // Tab 1: General (常規)
        enabledButton = new GuiButton(ENABLED, left, layout.contentRow(0), buttonWidth, 20, "");
        targetLanguageButton = new GuiButton(TARGET_LANGUAGE, right, layout.contentRow(0), buttonWidth, 20, "");
        displayButton = new GuiButton(DISPLAY, left, layout.contentRow(1), buttonWidth, 20, "");
        colorButton = new GuiButton(COLOR, right, layout.contentRow(1), buttonWidth, 20, "");
        cacheButton = new GuiButton(CACHE, left, layout.contentRow(2), buttonWidth, 20, "");
        uiStyleButton = new GuiButton(UI_STYLE, right, layout.contentRow(2), buttonWidth, 20, "");
        diagnosticsButton = new GuiButton(DIAGNOSTICS, left, layout.contentRow(3), totalWidth, 20,
                tr("screen.universal_translator.diagnostics.title"));

        buttonList.add(enabledButton);
        buttonList.add(targetLanguageButton);
        buttonList.add(displayButton);
        buttonList.add(colorButton);
        buttonList.add(cacheButton);
        buttonList.add(uiStyleButton);
        buttonList.add(diagnosticsButton);

        // Tab 2: Scopes (範圍)
        chatButton = new GuiButton(CHAT, left, layout.contentRow(0), buttonWidth, 20, "");
        otherButton = new GuiButton(OTHER, right, layout.contentRow(0), buttonWidth, 20, "");
        vanillaButton = new GuiButton(VANILLA, left, layout.contentRow(1), buttonWidth, 20, "");
        playerNamesButton = new GuiButton(PLAYER_NAMES, right, layout.contentRow(1), buttonWidth, 20, "");
        mixedTextButton = new GuiButton(MIXED_TEXT, left, layout.contentRow(2), totalWidth, 20, "");
        blockedKeywords = new GuiTextField(22, renderer, left, layout.contentRow(3), totalWidth, 20);
        blockedKeywords.setMaxStringLength(4096);
        blockedKeywords.setText(blockedKeywordsValue);

        buttonList.add(chatButton);
        buttonList.add(otherButton);
        buttonList.add(vanillaButton);
        buttonList.add(playerNamesButton);
        buttonList.add(mixedTextButton);

        // Tab 3: Engine (引擎)
        providerButton = new GuiButton(PROVIDER, left, layout.contentRow(0), totalWidth, 20, "");
        modelButton = new GuiButton(MODEL, left, layout.contentRow(1), buttonWidth, 20, "");
        downloadButton = new GuiButton(DOWNLOAD, right, layout.contentRow(1), buttonWidth, 20, "");
        fallbackButton = new GuiButton(FALLBACK, left, layout.contentRow(2), totalWidth, 20, "");
        llmSettingsButton = new GuiButton(LLM_SETTINGS, left, layout.contentRow(1), buttonWidth, 20,
                tr("screen.universal_translator.option.llm_settings"));
        endpoint = new GuiTextField(21, renderer, left, layout.contentRow(isLlm() ? 2 : 1), totalWidth, 20);
        endpoint.setMaxStringLength(512);
        endpoint.setText(endpointValue);

        buttonList.add(providerButton);
        buttonList.add(modelButton);
        buttonList.add(downloadButton);
        buttonList.add(fallbackButton);
        buttonList.add(llmSettingsButton);

        // Tab 4: Outgoing (傳送)
        outgoingButton = new GuiButton(OUTGOING, left, layout.contentRow(0), buttonWidth, 20, "");
        outgoingTargetButton = new GuiButton(OUTGOING_TARGET_LANGUAGE, right, layout.contentRow(0), buttonWidth, 20, "");

        buttonList.add(outgoingButton);
        buttonList.add(outgoingTargetButton);

        // Bottom Bar
        saveButton = new GuiButton(SAVE, left, layout.saveY(), buttonWidth, 20,
                tr("screen.universal_translator.save"));
        cancelButton = new GuiButton(CANCEL, right, layout.saveY(), buttonWidth, 20,
                tr("gui.cancel"));

        buttonList.add(saveButton);
        buttonList.add(cancelButton);

        updateTabVisibility();
        refreshLabels();
    }

    private void updateTabVisibility() {
        tabGeneralButton.enabled = activeTab != Tab.GENERAL;
        tabScopesButton.enabled = activeTab != Tab.SCOPES;
        tabEngineButton.enabled = activeTab != Tab.ENGINE;
        tabOutgoingButton.enabled = activeTab != Tab.OUTGOING;

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
        outgoingTargetButton.enabled = translateOutgoing;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
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
            mc.displayGuiScreen(new LegacyLlmConfigScreen(
                    this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
            return;
        } else if (button.id == FALLBACK) {
            apiFallback = !apiFallback;
        } else if (button.id == OUTGOING) {
            translateOutgoing = !translateOutgoing;
            outgoingTargetButton.enabled = translateOutgoing;
        } else if (button.id == PLAYER_NAMES) {
            translatePlayerNames = !translatePlayerNames;
        } else if (button.id == UI_STYLE) {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
        } else if (button.id == MODEL) {
            offlineModel = offlineModel.next();
        } else if (button.id == DIAGNOSTICS) {
            mc.displayGuiScreen(new LegacyDiagnosticsScreen(this));
            return;
        } else if (button.id == TARGET_LANGUAGE) {
            openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE;
        } else if (button.id == OUTGOING_TARGET_LANGUAGE) {
            openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE;
        } else if (button.id == SAVE) {
            saveAndApply();
            return;
        } else if (button.id == CANCEL) {
            mc.displayGuiScreen(parent);
            return;
        }
        refreshLabels();
    }

    private void refreshLabels() {
        if (uiStyleButton != null) {
            uiStyleButton.displayString = tr("screen.universal_translator.option.ui_style",
                    tr(animatedUi ? "value.universal_translator.ui_animated"
                            : "value.universal_translator.ui_classic"));
        }
        if (enabledButton != null) {
            enabledButton.displayString = tr("screen.universal_translator.option.automatic", onOff(enabled));
        }
        if (targetLanguageButton != null) {
            targetLanguageButton.displayString = tr("screen.universal_translator.option.target_preset",
                    TargetLanguage.displayName(targetLanguage));
        }
        if (displayButton != null) {
            displayButton.displayString = tr("screen.universal_translator.option.display",
                    tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                            ? "value.universal_translator.display_bilingual"
                            : "value.universal_translator.display_translated"));
        }
        if (colorButton != null) {
            colorButton.displayString = tr("screen.universal_translator.option.color", colorLabel(translatedTextColor));
        }
        if (cacheButton != null) {
            cacheButton.displayString = tr("screen.universal_translator.option.cache", onOff(diskCache));
        }
        if (chatButton != null) {
            chatButton.displayString = tr("screen.universal_translator.option.chat", onOff(translateChat));
        }
        if (otherButton != null) {
            otherButton.displayString = tr("screen.universal_translator.option.other", onOff(translateOther));
        }
        if (vanillaButton != null) {
            vanillaButton.displayString = tr("screen.universal_translator.option.vanilla", onOff(translateVanilla));
        }
        if (playerNamesButton != null) {
            playerNamesButton.displayString = tr(
                    "screen.universal_translator.option.player_names", onOff(translatePlayerNames));
        }
        if (mixedTextButton != null) {
            mixedTextButton.displayString = tr("screen.universal_translator.option.mixed", onOff(translateEnglishOnly));
        }
        if (providerButton != null) {
            providerButton.displayString = tr("screen.universal_translator.option.provider", providerLabel());
        }
        if (modelButton != null) {
            modelButton.displayString = tr("screen.universal_translator.option.model", offlineModel.displayName());
            modelButton.enabled = isOffline();
        }
        if (downloadButton != null) {
            downloadButton.displayString = tr("screen.universal_translator.option.download", onOff(offlineAutoDownload));
            downloadButton.enabled = isOffline();
        }
        if (fallbackButton != null) {
            fallbackButton.displayString = tr("screen.universal_translator.option.fallback", onOff(apiFallback));
            fallbackButton.enabled = isOffline();
        }
        if (outgoingButton != null) {
            outgoingButton.displayString = tr("screen.universal_translator.option.outgoing", onOff(translateOutgoing));
        }
        if (outgoingTargetButton != null) {
            outgoingTargetButton.displayString = tr(
                    "screen.universal_translator.option.outgoing_target",
                    TargetLanguage.displayName(outgoingTargetLanguage));
            outgoingTargetButton.enabled = translateOutgoing;
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
        if (activeTab == Tab.ENGINE && !isOffline() && endpoint != null) {
            endpoint.updateCursorCounter();
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null) {
            blockedKeywords.updateCursorCounter();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (activeTab == Tab.ENGINE && !isOffline() && endpoint != null && endpoint.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null && blockedKeywords.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (openSelection != SettingsSelectionList.Kind.NONE
                && selectFromList(mouseX, mouseY)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (activeTab == Tab.ENGINE && !isOffline() && endpoint != null) {
            endpoint.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null) {
            blockedKeywords.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
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
            drawRect(0, 0, width, height, 0x76070B10);
            drawRect(panelLeft - 2, 2, panelRight + 2, panelBottom + 2, 0x70101820);
            drawRect(panelLeft, 4, panelRight, panelBottom, 0xD41A232E);
            drawRect(panelLeft, 4, panelRight, 5, 0xCC55D6FF);
            drawRect(panelLeft, 32, panelRight, 33, 0x6655D6FF);
            int sweep = SettingsUiAnimation.sweepX(
                    panelLeft, Math.max(panelLeft, panelRight - 26), now);
            drawRect(sweep, 32, Math.min(panelRight, sweep + 26), 34,
                    SettingsUiAnimation.pulseColor(now));

            // Animated Tab underline
            int tabX = layout.tabX(activeTab.ordinal());
            int tabW = layout.tabWidth();
            drawRect(tabX, layout.tabY() + 20, tabX + tabW, layout.tabY() + 22, 0xFF55D6FF);
        } else {
            // Static active tab indicator
            int tabX = layout.tabX(activeTab.ordinal());
            int tabW = layout.tabWidth();
            drawRect(tabX, layout.tabY() + 20, tabX + tabW, layout.tabY() + 22, 0xFF55D6FF);
        }

        drawCenteredString(renderer, tr("screen.universal_translator.settings.title"),
                width / 2, 10,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);

        // Tab-specific text and fields
        if (activeTab == Tab.SCOPES) {
            blockedKeywords.drawTextBox();
            if (blockedKeywords.getText().isEmpty()) {
                renderer.drawStringWithShadow(
                        tr("screen.universal_translator.blocked_keywords_hint"),
                        layout.left() + 4, layout.contentRow(3) + 6, 0x70A0A0A0);
            }
        } else if (activeTab == Tab.ENGINE) {
            if (!isOffline()) {
                endpoint.drawTextBox();
                renderer.drawStringWithShadow(
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
        String rawRuntimeStatus = LegacyTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                LegacyConfigScreen::tr);
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
                drawRect(0, 0, width, height, overlayAlpha << 24);
            }
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            renderSelection(mouseX, mouseY);
        }
    }

    private void renderSelection(int mouseX, int mouseY) {
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        drawRect(0, 0, width, height, 0xB0080B10);
        drawRect(list.panelLeft() - 1, list.panelTop - 1,
                list.panelRight() + 1, list.panelBottom + 1, 0xFF55D6FF);
        drawRect(list.panelLeft(), list.panelTop,
                list.panelRight(), list.panelBottom, 0xF018202A);
        drawCenteredString(renderer, tr(selectionTitleKey()),
                width / 2, list.panelTop + 9, 0xFFFFFFFF);
        for (int index = 0; index < values.length; index++) {
            int x = list.x(index);
            int y = list.y(index);
            boolean hovered = mouseX >= x && mouseX < x + list.buttonWidth
                    && mouseY >= y && mouseY < y + list.buttonHeight;
            boolean selected = values[index].equalsIgnoreCase(selectionValue());
            drawRect(x, y, x + list.buttonWidth, y + list.buttonHeight,
                    hovered ? 0xFF3B6178 : selected ? 0xFF28533D : 0xFF303844);
            drawCenteredString(renderer,
                    SettingsSelectionList.displayName(openSelection, values[index]),
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
                openSelection = SettingsSelectionList.Kind.NONE;
                initGui();
                return true;
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
        if (openSelection == SettingsSelectionList.Kind.PROVIDER) return "screen.universal_translator.selection.provider";
        if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) return "screen.universal_translator.selection.target_language";
        return "screen.universal_translator.selection.outgoing_language";
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
        return I18n.format(key, arguments);
    }
}
