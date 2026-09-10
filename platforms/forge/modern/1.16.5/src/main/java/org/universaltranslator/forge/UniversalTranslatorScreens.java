package org.universaltranslator.forge;

import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.universaltranslator.core.DiagnosticsLogExporter;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.SettingsSelectionList;
import org.universaltranslator.core.SettingsUiAnimation;
import org.universaltranslator.core.SettingsScreenLayout;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.TranslationProviderCatalog;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;

import java.util.Collections;
import java.util.List;

/** Shared state and layout for Forge 1.16.5 screens with a MatrixStack render bridge. */
abstract class UniversalTranslatorConfigScreenBase extends Screen {
    private final Screen parent;
    private final ForgeConfig original;
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
    private Button tabGeneralButton;
    private Button tabScopesButton;
    private Button tabEngineButton;
    private Button tabOutgoingButton;
    private Button llmConfigButton;

    private TextFieldWidget endpoint;
    private TextFieldWidget blockedKeywords;
    private Button enabledButton;
    private Button uiStyleButton;
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
    private Button mixedTextButton;
    private Button colorButton;
    private Button outgoingButton;
    private Button targetLanguageButton;
    private Button outgoingTargetLanguageButton;
    private Button diagnosticsButton;
    private String status = "";
    private long animationStartedNanos = System.nanoTime();
    private SettingsSelectionList.Kind openSelection = SettingsSelectionList.Kind.NONE;

    UniversalTranslatorConfigScreenBase(Screen parent, ForgeConfig config) {
        super(new TranslationTextComponent("screen.universal_translator.settings.title"));
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
    protected final void init() {
        if (targetLanguage == null) {
            targetLanguage = TargetLanguage.canonicalize(original.targetLanguage);
        }
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getValue();
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getValue();
        Layout layout = layout();
        int left = layout.left;

        // Navigation Tabs (positioned right below header at tabY)
        tabGeneralButton = addButton(button(layout.tabX(0), layout.tabY(), layout.tabWidth(), () -> {
            activeTab = Tab.GENERAL;
            updateTabVisibility();
        }));
        tabScopesButton = addButton(button(layout.tabX(1), layout.tabY(), layout.tabWidth(), () -> {
            activeTab = Tab.SCOPES;
            updateTabVisibility();
        }));
        tabEngineButton = addButton(button(layout.tabX(2), layout.tabY(), layout.tabWidth(), () -> {
            activeTab = Tab.ENGINE;
            updateTabVisibility();
        }));
        tabOutgoingButton = addButton(button(layout.tabX(3), layout.tabY(), layout.tabWidth(), () -> {
            activeTab = Tab.OUTGOING;
            updateTabVisibility();
        }));

        // --- Tab 1: General (常規) ---
        enabledButton = addButton(button(left, layout.contentRow(0), layout.buttonWidth,
                () -> enabled = !enabled));
        targetLanguageButton = addButton(button(layout.right, layout.contentRow(0), layout.buttonWidth,
                () -> openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE));
        displayButton = addButton(button(left, layout.contentRow(1), layout.buttonWidth,
                () -> displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? TranslationDisplayMode.TRANSLATED_ONLY
                        : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED));
        colorButton = addButton(button(layout.right, layout.contentRow(1), layout.buttonWidth,
                () -> translatedTextColor = translatedTextColor.next()));
        cacheButton = addButton(button(left, layout.contentRow(2), layout.buttonWidth,
                () -> diskCache = !diskCache));
        uiStyleButton = addButton(button(layout.right, layout.contentRow(2), layout.buttonWidth, () -> {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
        }));
        diagnosticsButton = addButton(new Button(left, layout.contentRow(3), layout.totalWidth, 20,
                new TranslationTextComponent("screen.universal_translator.diagnostics.title"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new UniversalTranslatorDiagnosticsScreen(this, original));
                    }
                }));

        // --- Tab 2: Scopes (範圍) ---
        chatButton = addButton(button(left, layout.contentRow(0), layout.buttonWidth,
                () -> translateChat = !translateChat));
        otherButton = addButton(button(layout.right, layout.contentRow(0), layout.buttonWidth,
                () -> translateOther = !translateOther));
        vanillaButton = addButton(button(left, layout.contentRow(1), layout.buttonWidth,
                () -> translateVanilla = !translateVanilla));
        playerNamesButton = addButton(button(layout.right, layout.contentRow(1), layout.buttonWidth,
                () -> translatePlayerNames = !translatePlayerNames));
        mixedTextButton = addButton(button(left, layout.contentRow(2), layout.totalWidth,
                () -> translateEnglishOnly = !translateEnglishOnly));
        blockedKeywords = addButton(new TextFieldWidget(font, left, layout.contentRow(3),
                layout.totalWidth, 20, new TranslationTextComponent("screen.universal_translator.blocked_keywords")));
        blockedKeywords.setMaxLength(4096);
        blockedKeywords.setValue(blockedKeywordsValue);

        // --- Tab 3: Engine (引擎) ---
        providerButton = addButton(button(left, layout.contentRow(0), layout.totalWidth,
                () -> openSelection = SettingsSelectionList.Kind.PROVIDER));
        modelButton = addButton(button(left, layout.contentRow(1), layout.buttonWidth,
                () -> offlineModel = offlineModel.next()));
        downloadButton = addButton(button(layout.right, layout.contentRow(1), layout.buttonWidth,
                () -> offlineAutoDownload = !offlineAutoDownload));
        endpoint = addButton(new TextFieldWidget(font, left, layout.contentRow(1), layout.totalWidth,
                20, new TranslationTextComponent("screen.universal_translator.endpoint")));
        endpoint.setMaxLength(512);
        endpoint.setValue(endpointValue);
        fallbackButton = addButton(button(left, layout.contentRow(2), layout.totalWidth,
                () -> apiFallback = !apiFallback));
        llmConfigButton = addButton(new Button(left, layout.contentRow(2), layout.totalWidth, 20,
                new TranslationTextComponent("screen.universal_translator.option.llm_settings"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new UniversalTranslatorLlmConfigScreen(
                                this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
                    }
                }));

        // --- Tab 4: Outgoing (傳送) ---
        outgoingButton = addButton(button(left, layout.contentRow(0), layout.totalWidth,
                () -> translateOutgoing = !translateOutgoing));
        outgoingTargetLanguageButton = addButton(button(left, layout.contentRow(1),
                layout.totalWidth, () -> openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE));

        // --- Bottom Action Row ---
        addButton(new Button(left, layout.saveY, layout.buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.save"), button -> saveAndApply()));
        addButton(new Button(layout.right, layout.saveY, layout.buttonWidth, 20,
                new TranslationTextComponent("gui.cancel"), button -> onClose()));

        refreshLabels();
        updateTabVisibility();
    }

    private Button button(int x, int y, int width, Runnable action) {
        return new Button(x, y, width, 20, StringTextComponent.EMPTY, button -> {
            action.run();
            refreshLabels();
        });
    }

    private void refreshLabels() {
        uiStyleButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.ui_style",
                tr(animatedUi ? "value.universal_translator.ui_animated"
                        : "value.universal_translator.ui_classic")));
        enabledButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.automatic", onOff(enabled)));
        chatButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.chat", onOff(translateChat)));
        otherButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.other", onOff(translateOther)));
        vanillaButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        playerNamesButton.setMessage(new TranslationTextComponent(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames)));
        cacheButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.cache", onOff(diskCache)));
        providerButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.provider",
                TranslationProviderCatalog.displayName(provider)));
        displayButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        mixedTextButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.mixed", onOff(translateEnglishOnly)));
        colorButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.color", colorLabel(translatedTextColor)));
        downloadButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        modelButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.model", offlineModel.displayName()));
        fallbackButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.fallback", onOff(apiFallback)));
        outgoingButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.outgoing", onOff(translateOutgoing)));
        targetLanguageButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage)));
        outgoingTargetLanguageButton.setMessage(new TranslationTextComponent(
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

    private ITextComponent tabTitle(String key, boolean active) {
        String label = tr(key);
        return new StringTextComponent(active ? "§b§l[ " + label + " ]" : "§7" + label);
    }

    private void saveAndApply() {
        boolean runtimeChanged = false;
        try {
            ForgeConfig updated = original.withSettings(
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
            ForgeTranslationRuntime.initialize(updated);
            updated.save();
            status = tr("status.universal_translator.saved");
            onClose();
        } catch (Exception exception) {
            if (runtimeChanged) {
                try {
                    ForgeTranslationRuntime.initialize(original);
                } catch (Exception restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            status = tr("status.universal_translator.save_failed", exception.getMessage());
        }
    }

    protected final float renderSettingsBefore(ForgeScreenCanvas graphics) {
        Layout layout = layout();
        long now = System.nanoTime();
        float opening = 1.0F;
        if (animatedUi) {
            opening = SettingsUiAnimation.openProgress(animationStartedNanos, now);
            int center = width / 2;
            int half = SettingsUiAnimation.expandingHalfWidth(layout.totalWidth / 2 + 12, opening);
            int panelLeft = center - half;
            int panelRight = center + half;
            int panelBottom = Math.min(height - 4, layout.saveY + 42);
            graphics.fill(0, 0, width, height, 0x76070B10);
            graphics.fill(panelLeft - 2, 2, panelRight + 2, panelBottom + 2, 0x70101820);
            graphics.fill(panelLeft, 4, panelRight, panelBottom, 0xD41A232E);
            graphics.fill(panelLeft, 4, panelRight, 5, 0xCC55D6FF);
            graphics.fill(panelLeft, 32, panelRight, 33, 0x6655D6FF);
            int sweep = SettingsUiAnimation.sweepX(panelLeft, Math.max(panelLeft, panelRight - 26), now);
            graphics.fill(sweep, 32, Math.min(panelRight, sweep + 26), 34,
                    SettingsUiAnimation.pulseColor(now));

            // Glowing underline for active tab
            int tabIndex = activeTab.ordinal();
            int tabX = layout.tabX(tabIndex);
            graphics.fill(tabX, layout.tabY() + 20, tabX + layout.tabWidth(), layout.tabY() + 22, 0xFF55D6FF);
        }
        graphics.centered(title, width / 2, 16,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFFFF);
        String rawStatus = ForgeTranslationRuntime.status();
        String localized = TranslationStatusLocalizer.localize(rawStatus,
                UniversalTranslatorConfigScreenBase::tr);
        int belowSave = layout.saveY + 24;
        int messageY = belowSave <= height - 10 ? belowSave : SettingsScreenLayout.COMPACT_STATUS_Y;
        if (!status.isEmpty()) {
            graphics.centered(new StringTextComponent(status), width / 2, messageY, 0xFFFF5555);
        } else if (!localized.isEmpty()) {
            graphics.centered(new StringTextComponent(localized), width / 2, messageY,
                    TranslationStatusLocalizer.isFailure(rawStatus) ? 0xFFFF5555 : 0xFF55FF55);
        } else {
            if (activeTab == Tab.OUTGOING) {
                graphics.centered(new TranslationTextComponent("screen.universal_translator.info.outgoing_hint"),
                        width / 2, layout.contentRow(2) + 6, 0xFFA0A0A0);
            } else if (activeTab == Tab.GENERAL) {
                graphics.centered(new TranslationTextComponent("screen.universal_translator.info.keybind"),
                        width / 2, layout.contentRow(3) + 24, 0xFFA0A0A0);
            } else if (activeTab == Tab.ENGINE) {
                graphics.centered(new TranslationTextComponent(isOffline()
                                ? "screen.universal_translator.info.offline"
                                : "screen.universal_translator.info.api"),
                        width / 2, layout.contentRow(3) + 6, 0xFFFFAA55);
            }
        }
        return opening;
    }

    protected final void renderSettingsAfter(ForgeScreenCanvas graphics, int mouseX, int mouseY,
                                              float opening) {
        if (animatedUi) {
            int overlayAlpha = SettingsUiAnimation.openingOverlayAlpha(opening);
            if (overlayAlpha > 0) {
                graphics.fill(0, 0, width, height, overlayAlpha << 24);
            }
        }
        if (openSelection == SettingsSelectionList.Kind.NONE) {
            return;
        }
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        graphics.fill(0, 0, width, height, 0xB0080B10);
        graphics.fill(list.panelLeft() - 1, list.panelTop - 1,
                list.panelRight() + 1, list.panelBottom + 1, 0xFF55D6FF);
        graphics.fill(list.panelLeft(), list.panelTop,
                list.panelRight(), list.panelBottom, 0xF018202A);
        graphics.centered(new TranslationTextComponent(selectionTitleKey()),
                width / 2, list.panelTop + 9, 0xFFFFFFFF);
        for (int index = 0; index < values.length; index++) {
            int x = list.x(index);
            int y = list.y(index);
            boolean hovered = mouseX >= x && mouseX < x + list.buttonWidth
                    && mouseY >= y && mouseY < y + list.buttonHeight;
            boolean selected = values[index].equalsIgnoreCase(selectionValue());
            graphics.fill(x, y, x + list.buttonWidth, y + list.buttonHeight,
                    hovered ? 0xFF3B6178 : selected ? 0xFF28533D : 0xFF303844);
            graphics.centered(new StringTextComponent(
                    SettingsSelectionList.displayName(openSelection, values[index])),
                    x + list.buttonWidth / 2, y + Math.max(1, (list.buttonHeight - 8) / 2),
                    selected ? 0xFF55FF88 : 0xFFFFFFFF);
        }
    }

    protected final boolean handleSelectionClick(double mouseX, double mouseY) {
        if (openSelection == SettingsSelectionList.Kind.NONE) {
            return false;
        }
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        int selected = list.optionAt(mouseX, mouseY, values.length);
        if (selected >= 0) {
            if (openSelection == SettingsSelectionList.Kind.PROVIDER) {
                provider = values[selected];
                loadLlmSettings(provider);
            }
            else if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) targetLanguage = values[selected];
            else outgoingTargetLanguage = values[selected];
            openSelection = SettingsSelectionList.Kind.NONE;
            refreshLabels();
            updateTabVisibility();
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
    public final void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }

    private void loadLlmSettings(String selectedProvider) {
        if (!TranslationProviderCatalog.usesLlmEditor(selectedProvider)) {
            return;
        }
        this.llmEndpoint = original.editorEndpoint(selectedProvider);
        this.llmApiKey = original.editorApiKey(selectedProvider);
        this.llmModel = original.editorModel(selectedProvider);
    }

    final void applyLlmSettings(String endpoint, String model, String apiKey) {
        this.llmEndpoint = endpoint;
        this.llmModel = model;
        this.llmApiKey = apiKey;
    }

    final String llmApiKey() {
        return llmApiKey;
    }

    private boolean isOffline() {
        return "offline".equalsIgnoreCase(provider);
    }

    private boolean isLlm() {
        return TranslationProviderCatalog.usesLlmEditor(provider);
    }

    private static String onOff(boolean value) {
        return tr(value ? "value.universal_translator.on" : "value.universal_translator.off");
    }

    private static String colorLabel(TranslationTextColor color) {
        return tr("value.universal_translator.color." + color.configName().replace('-', '_'));
    }

    private static String tr(String key, Object... arguments) {
        return new TranslationTextComponent(key, arguments).getString();
    }

    private Layout layout() {
        SettingsScreenLayout.Geometry geometry = SettingsScreenLayout.calculate(width, height);
        return new Layout(geometry.left(), geometry.right(), geometry.totalWidth(), geometry.buttonWidth(),
                geometry.top(), geometry.rowStep(), geometry.targetY(), geometry.endpointY(), geometry.saveY(),
                geometry.tabY(), geometry.tabWidth(), geometry.tabGap(), geometry.contentTop(), geometry.contentRowStep());
    }

    private static final class Layout {
        private final int left, right, totalWidth, buttonWidth, top, rowStep, targetY, endpointY, saveY;
        private final int tabY, tabWidth, tabGap, contentTop, contentRowStep;

        private Layout(int left, int right, int totalWidth, int buttonWidth, int top,
                       int rowStep, int targetY, int endpointY, int saveY,
                       int tabY, int tabWidth, int tabGap, int contentTop, int contentRowStep) {
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
            this.contentRowStep = contentRowStep;
        }

        private int tabX(int index) {
            return left + index * (tabWidth + tabGap);
        }

        private int tabY() {
            return tabY;
        }

        private int tabWidth() {
            return tabWidth;
        }

        private int contentRow(int index) {
            return contentTop + contentRowStep * index;
        }

        private int row(int index) {
            return top + rowStep * index;
        }
    }
}

abstract class UniversalTranslatorDiagnosticsScreenBase extends Screen {
    private final Screen parent;
    private final ForgeConfig config;
    private String exportStatus = "";
    private boolean exportFailed;

    UniversalTranslatorDiagnosticsScreenBase(Screen parent, ForgeConfig config) {
        super(new TranslationTextComponent("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected final void init() {
        int totalWidth = Math.max(180, Math.min(320, width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;
        addButton(new Button(left, height - 28, buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.diagnostics.back"), button -> onClose()));
        addButton(new Button(left + buttonWidth + gap, height - 28, buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.diagnostics.export"), button -> exportLog()));
    }

    protected final void renderDiagnostics(ForgeScreenCanvas graphics) {
        graphics.fill(0, 0, width, height, 0xE510151C);
        graphics.fill(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        graphics.centered(title, width / 2, 18, 0xFFFFFFFF);
        List<String> lines = safeLines();
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            graphics.text(new StringTextComponent(line), left, y, 0xFFD0D0D0);
            y += 17;
        }
        graphics.centered(new TranslationTextComponent("screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 58), 0xFF909090);
        if (!exportStatus.isEmpty()) {
            graphics.centered(new StringTextComponent(exportStatus), width / 2, height - 43,
                    exportFailed ? 0xFFFF5555 : 0xFF55FF88);
        }
    }

    private List<String> safeLines() {
        try {
            TranslationDiagnosticsSnapshot snapshot = ForgeTranslationRuntime.diagnostics();
            return snapshot == null
                    ? Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"))
                    : snapshot.localizedLines(UniversalTranslatorDiagnosticsScreenBase::tr);
        } catch (RuntimeException ignored) {
            return Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    private void exportLog() {
        try {
            DiagnosticsLogExporter.export(config.offlineDirectory.getParent()
                    .resolve("universal-translator-diagnostics"), safeLines());
            exportStatus = tr("screen.universal_translator.diagnostics.exported");
            exportFailed = false;
        } catch (Exception ignored) {
            exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            exportFailed = true;
        }
    }

    @Override
    public final void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return new TranslationTextComponent(key, arguments).getString();
    }
}

abstract class UniversalTranslatorLlmConfigScreenBase extends Screen {
    private final UniversalTranslatorConfigScreenBase parent;
    private final String initialEndpoint;
    private final String initialModel;
    private final boolean hasStoredKey;
    private TextFieldWidget endpoint;
    private TextFieldWidget model;
    private TextFieldWidget apiKey;
    private String status = "";

    UniversalTranslatorLlmConfigScreenBase(UniversalTranslatorConfigScreenBase parent,
                                            String endpoint, String model, boolean hasStoredKey) {
        super(new TranslationTextComponent("screen.universal_translator.llm.title"));
        this.parent = parent;
        this.initialEndpoint = endpoint;
        this.initialModel = model;
        this.hasStoredKey = hasStoredKey;
    }

    @Override
    protected final void init() {
        int formWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - formWidth) / 2;
        int top = Math.max(42, (height - 150) / 2);
        endpoint = addButton(new TextFieldWidget(font, left, top, formWidth, 20,
                new TranslationTextComponent("screen.universal_translator.llm.endpoint")));
        endpoint.setMaxLength(512);
        endpoint.setValue(initialEndpoint);
        model = addButton(new TextFieldWidget(font, left, top + 36, formWidth, 20,
                new TranslationTextComponent("screen.universal_translator.llm.model")));
        model.setMaxLength(128);
        model.setValue(initialModel);
        apiKey = addButton(new TextFieldWidget(font, left, top + 72, formWidth, 20,
                new TranslationTextComponent("screen.universal_translator.llm.api_key")));
        apiKey.setMaxLength(512);
        int gap = 8;
        int buttonWidth = (formWidth - gap) / 2;
        addButton(new Button(left, top + 108, buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.llm.save"), button -> save()));
        addButton(new Button(left + buttonWidth + gap, top + 108, buttonWidth, 20,
                new TranslationTextComponent("gui.cancel"), button -> onClose()));
    }

    protected final void renderLlm(ForgeScreenCanvas graphics) {
        int formWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - formWidth) / 2;
        int top = Math.max(42, (height - 150) / 2);
        graphics.centered(title, width / 2, 18, 0xFFFFFFFF);
        graphics.text(new TranslationTextComponent("screen.universal_translator.llm.endpoint_hint"),
                left, top - 11, 0xFFA0A0A0);
        graphics.text(new TranslationTextComponent("screen.universal_translator.llm.model_hint"),
                left, top + 25, 0xFFA0A0A0);
        graphics.text(new TranslationTextComponent(hasStoredKey
                        ? "screen.universal_translator.llm.key_saved_hint"
                        : "screen.universal_translator.llm.key_empty_hint"),
                left, top + 61, 0xFFA0A0A0);
        if (!status.isEmpty()) {
            graphics.centered(new StringTextComponent(status), width / 2, top + 134, 0xFFFF5555);
        }
    }

    private void save() {
        String endpointValue = endpoint.getValue().trim();
        String modelValue = model.getValue().trim();
        if (endpointValue.isEmpty() || modelValue.isEmpty()) {
            status = new TranslationTextComponent("error.universal_translator.llm_required").getString();
            return;
        }
        String enteredKey = apiKey.getValue().trim();
        String keyValue = enteredKey.isEmpty()
                ? parent.llmApiKey() : ("-".equals(enteredKey) ? "" : enteredKey);
        parent.applyLlmSettings(endpointValue, modelValue, keyValue);
        onClose();
    }

    @Override
    public final void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }
}

interface ForgeScreenCanvas {
    void fill(int left, int top, int right, int bottom, int color);
    void centered(ITextComponent text, int centerX, int y, int color);
    void text(ITextComponent text, int x, int y, int color);
}
