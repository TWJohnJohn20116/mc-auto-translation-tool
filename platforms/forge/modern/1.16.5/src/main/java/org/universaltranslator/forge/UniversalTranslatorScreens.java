package org.universaltranslator.forge;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.DiagnosticsBundleExporter;
import org.universaltranslator.core.DiagnosticsLogExporter;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.SettingsSelectionList;
import org.universaltranslator.core.SettingsUiAnimation;
import org.universaltranslator.core.SettingsScreenLayout;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationProviderCatalog;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.provider.OpenAiModelCatalog;
import org.universaltranslator.core.provider.ProviderProbe;

import java.net.URI;
import java.nio.file.Path;
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
    private boolean hudIndicator;
    private boolean diskCache;
    private boolean offlineAutoDownload;
    private OfflineModel offlineModel;
    private boolean apiFallback;
    private TranslationDisplayMode displayMode;
    private boolean translateEnglishOnly;
    private TranslationTextColor translatedTextColor;
    private HudIndicatorCorner hudIndicatorCorner;
    private int hudIndicatorSize;
    private int hudIndicatorMargin;
    private HudIndicatorColor hudIndicatorColor;
    private HudIndicatorContent hudIndicatorContent;
    private HudIndicatorVisibility hudIndicatorVisibility;
    private int hudIndicatorOffsetX;
    private int hudIndicatorOffsetY;
    private boolean hudDragging;
    private double hudDragLastX;
    private double hudDragLastY;
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
        OUTGOING,
        HUD
    }
    private Tab activeTab = Tab.GENERAL;
    private Button tabGeneralButton;
    private Button tabScopesButton;
    private Button tabEngineButton;
    private Button tabOutgoingButton;
    private Button tabHudButton;
    private Button llmConfigButton;
    private Button modelPickButton;
    private Button fetchModelsButton;
    private Button testConnectionButton;
    private Button checkSettingsButton;

    private TextFieldWidget endpoint;
    /** Selected quality mode; the shared Save writes it and Apply commits it immediately. */
    private TranslationQuality quality;
    private Button qualityButton;
    private TextFieldWidget customPrompt;
    private Button applyPromptButton;
    private TextFieldWidget blockedKeywords;
    private Button enabledButton;
    private Button uiStyleButton;
    private Button hudIndicatorButton;
    private Button hudCornerButton;
    private Button hudSizeButton;
    private Button hudMarginButton;
    private Button hudColorButton;
    private Button hudContentButton;
    private Button hudVisibilityButton;
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
    /** Result of the engine tab's connection test; written by its worker thread. */
    private volatile String testStatus = "";
    private volatile boolean testStatusIsError;
    private boolean testing;
    private boolean fetchingModels;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean modelListOpen;
    private int modelPage;
    private long animationStartedNanos = System.nanoTime();
    private SettingsSelectionList.Kind openSelection = SettingsSelectionList.Kind.NONE;

    /** The preview panel is a miniature of Minecraft's 320x240 minimum logical screen. */
    private static final int HUD_PREVIEW_SCREEN_WIDTH = 320;
    private static final int HUD_PREVIEW_SCREEN_HEIGHT = 240;
    /** Tallest the preview panel may be. It shrinks on short windows so it never hits Save. */
    private static final int HUD_DRAG_PREVIEW_HEIGHT = 48;
    private static final int HUD_DRAG_HANDLE_SIZE = 8;
    /** Height of one row in the engine tab's model picker overlay. */
    private static final int MODEL_ROW_HEIGHT = 12;
    /** Y of the first model row; the picker title sits above it. */
    private static final int MODEL_LIST_TOP = 46;
    /** Room kept under the model rows for the paging, back, and hint lines. */
    private static final int MODEL_LIST_FOOTER_HEIGHT = 62;
    /** Longest custom prompt the engine tab's text field accepts. */
    private static final int MAXIMUM_PROMPT_LENGTH = 4096;
    private static final String LIBRETRANSLATE_PROVIDER = "libretranslate";
    private static final String TRANSLATE_PATH = "/translate";
    private static final String LANGUAGES_PATH = "/languages";

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
        this.hudIndicator = config.hudIndicator;
        this.diskCache = config.diskCache;
        this.offlineAutoDownload = config.offlineAutoDownload;
        this.offlineModel = config.offlineModel;
        this.apiFallback = config.apiFallback;
        this.displayMode = config.displayMode;
        this.translateEnglishOnly = config.translateEnglishOnly;
        this.translatedTextColor = config.translatedTextColor;
        this.hudIndicatorCorner = config.hudIndicatorCorner;
        this.hudIndicatorSize = config.hudIndicatorSize;
        this.hudIndicatorMargin = config.hudIndicatorMargin;
        this.hudIndicatorColor = config.hudIndicatorColor;
        this.hudIndicatorContent = config.hudIndicatorContent;
        this.hudIndicatorVisibility = config.hudIndicatorVisibility;
        this.hudIndicatorOffsetX = config.hudIndicatorOffsetX;
        this.hudIndicatorOffsetY = config.hudIndicatorOffsetY;
        this.provider = config.provider;
        this.llmEndpoint = config.editorEndpoint(config.provider);
        this.llmApiKey = config.editorApiKey(config.provider);
        this.llmModel = config.editorModel(config.provider);
        this.quality = config.translationQuality();
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
        tabHudButton = addButton(button(layout.tabX(4), layout.tabY(), layout.tabWidth(), () -> {
            activeTab = Tab.HUD;
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
        // The engine rows are centred in the free band (see engineRow) so the tab does not sit in
        // the top third of an otherwise empty panel, and every row is reachable without moving the
        // shared Save/Cancel row that the other tabs and the HUD preview depend on.
        providerButton = addButton(button(left, engineRow(0), layout.totalWidth,
                () -> openSelection = SettingsSelectionList.Kind.PROVIDER));
        modelButton = addButton(button(left, engineRow(1), layout.buttonWidth,
                () -> offlineModel = offlineModel.next()));
        downloadButton = addButton(button(layout.right, engineRow(1), layout.buttonWidth,
                () -> offlineAutoDownload = !offlineAutoDownload));
        modelPickButton = addButton(button(left, engineRow(1), layout.buttonWidth, () -> {
            if (fetchedModels.isEmpty()) {
                fetchModels();
            } else {
                modelListOpen = true;
            }
        }));
        fetchModelsButton = addButton(new Button(layout.right, engineRow(1), layout.buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.llm.fetch_models"),
                button -> fetchModels()));
        llmConfigButton = addButton(new Button(left, engineRow(2), layout.buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.option.llm_settings"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new UniversalTranslatorLlmConfigScreen(
                                this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
                    }
                }));
        testConnectionButton = addButton(button(layout.right, engineRow(2), layout.buttonWidth,
                () -> testConnection()));
        checkSettingsButton = addButton(button(left, engineRow(2), layout.totalWidth,
                () -> testConnection()));
        fallbackButton = addButton(button(left, engineRow(2), layout.totalWidth,
                () -> apiFallback = !apiFallback));
        endpoint = addButton(new TextFieldWidget(font, left, engineRow(1), layout.totalWidth,
                20, new TranslationTextComponent("screen.universal_translator.endpoint")));
        endpoint.setMaxLength(512);
        endpoint.setValue(endpointValue);

        // Quality and the custom system prompt are engine settings, so they belong on
        // this tab rather than on the diagnostics screen. They take the two rows under the
        // existing three, which leaves every other engine control exactly where it was;
        // the prompt shares its row with its own Apply button.
        qualityButton = addButton(new Button(left, engineRow(3), layout.totalWidth, 20, new StringTextComponent(""), button -> {
            quality = quality.next();
            refreshQualityLabel();
        }));

        String promptValue = customPrompt == null
                ? TranslationPrompt.toEditorText(original.customSystemPrompt())
                : customPrompt.getValue();
        int promptApplyWidth = Math.max(48, layout.buttonWidth - 24);
        customPrompt = addButton(new TextFieldWidget(font, left, engineRow(4),
                layout.totalWidth - promptApplyWidth - 8,
                20, new TranslationTextComponent("screen.universal_translator.diagnostics.prompt")));
        customPrompt.setMaxLength(MAXIMUM_PROMPT_LENGTH);
        customPrompt.setValue(promptValue);
        applyPromptButton = addButton(new Button(layout.right + layout.buttonWidth - promptApplyWidth, engineRow(4), promptApplyWidth, 20,
                new TranslationTextComponent("screen.universal_translator.diagnostics.prompt_apply", button -> applyPromptSettings()));

        // --- Tab 4: Outgoing (傳送) ---
        outgoingButton = addButton(button(left, layout.contentRow(0), layout.totalWidth,
                () -> translateOutgoing = !translateOutgoing));
        outgoingTargetLanguageButton = addButton(button(left, layout.contentRow(1),
                layout.totalWidth, () -> openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE));

        // --- Tab 5: HUD (抬頭顯示) ---
        hudIndicatorButton = addButton(button(left, layout.contentRow(0), layout.buttonWidth,
                () -> hudIndicator = !hudIndicator));
        hudCornerButton = addButton(button(layout.right, layout.contentRow(0), layout.buttonWidth, () -> {
            HudIndicatorCorner[] corners = HudIndicatorCorner.values();
            hudIndicatorCorner = corners[(hudIndicatorCorner.ordinal() + 1) % corners.length];
        }));
        hudSizeButton = addButton(button(left, layout.contentRow(1), layout.buttonWidth,
                () -> hudIndicatorSize = hudIndicatorSize >= HudIndicatorSettings.MAX_SIZE
                        ? HudIndicatorSettings.MIN_SIZE : hudIndicatorSize + 1));
        hudMarginButton = addButton(button(layout.right, layout.contentRow(1), layout.buttonWidth,
                () -> hudIndicatorMargin = hudIndicatorMargin >= HudIndicatorSettings.MAX_MARGIN
                        ? HudIndicatorSettings.MIN_MARGIN : hudIndicatorMargin + 1));
        hudColorButton = addButton(button(left, layout.contentRow(2), layout.buttonWidth, () -> {
            HudIndicatorColor[] colors = HudIndicatorColor.values();
            hudIndicatorColor = colors[(hudIndicatorColor.ordinal() + 1) % colors.length];
        }));
        hudContentButton = addButton(button(layout.right, layout.contentRow(2), layout.buttonWidth, () -> {
            HudIndicatorContent[] contents = HudIndicatorContent.values();
            hudIndicatorContent = contents[(hudIndicatorContent.ordinal() + 1) % contents.length];
        }));
        hudVisibilityButton = addButton(button(left, layout.contentRow(3), layout.buttonWidth, () -> {
            HudIndicatorVisibility[] visibilities = HudIndicatorVisibility.values();
            hudIndicatorVisibility = visibilities[(hudIndicatorVisibility.ordinal() + 1) % visibilities.length];
        }));

        // --- Bottom Action Row ---
        addButton(new Button(left, layout.saveY, layout.buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.save"), button -> saveAndApply()));
        addButton(new Button(layout.right, layout.saveY, layout.buttonWidth, 20,
                new TranslationTextComponent("gui.cancel"), button -> onClose()));

        refreshLabels();
        updateTabVisibility();

        // A resize or a screen rebuild mid-drag must not leave the handle stuck to the pointer.
        hudDragging = false;
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
        hudIndicatorButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.hud_indicator",
                onOff(hudIndicator)));
        hudCornerButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.hud_corner",
                cornerLabel(hudIndicatorCorner)));
        hudSizeButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.hud_size",
                Integer.toString(hudIndicatorSize)));
        hudMarginButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.hud_margin",
                Integer.toString(hudIndicatorMargin)));
        hudColorButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.hud_color",
                hudColorLabel(hudIndicatorColor)));
        hudContentButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.hud_content",
                hudContentLabel(hudIndicatorContent)));
        hudVisibilityButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.hud_visibility",
                hudVisibilityLabel(hudIndicatorVisibility)));
        enabledButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.automatic", onOff(enabled)));
        chatButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.chat", onOff(translateChat)));
        otherButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.other", onOff(translateOther)));
        vanillaButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        playerNamesButton.setMessage(new TranslationTextComponent(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames)));
        cacheButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.cache", onOff(diskCache)));
        providerButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.provider",
                TranslationProviderCatalog.displayName(provider)));
        String modelValue = llmModel == null || llmModel.trim().isEmpty()
                ? tr("screen.universal_translator.engine.key_unset") : llmModel.trim();
        modelPickButton.setMessage(new TranslationTextComponent(
                "screen.universal_translator.engine.model", modelValue));
        testConnectionButton.setMessage(new TranslationTextComponent("screen.universal_translator.engine.test"));
        checkSettingsButton.setMessage(new TranslationTextComponent(isLibreTranslate()
                ? "screen.universal_translator.engine.test"
                : "screen.universal_translator.engine.check"));
        displayButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        mixedTextButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.mixed", onOff(translateEnglishOnly)));
        colorButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.color", colorLabel(translatedTextColor)));
        downloadButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        modelButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.model", offlineModel.displayName()));
        fallbackButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.fallback", onOff(apiFallback)));
        refreshQualityLabel();
        outgoingButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.outgoing", onOff(translateOutgoing)));
        targetLanguageButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage)));
        outgoingTargetLanguageButton.setMessage(new TranslationTextComponent(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage)));
        outgoingTargetLanguageButton.active = translateOutgoing;
        hudCornerButton.active = hudIndicator;
        hudSizeButton.active = hudIndicator;
        hudMarginButton.active = hudIndicator;
        hudColorButton.active = hudIndicator;
        hudContentButton.active = hudIndicator;
        hudVisibilityButton.active = hudIndicator;
        refreshTabButtons();
    }

    /**
     * Re-renders the quality button from the pending selection.
     *
     * <p>Like the other engine rows the button only changes what the shared Save writes; the Apply
     * button beside the prompt is there for users who want the change to take effect without
     * closing the screen.
     */
    private void refreshQualityLabel() {
        if (qualityButton == null) {
            return;
        }
        qualityButton.setMessage(new TranslationTextComponent(
                "screen.universal_translator.option.quality",
                tr("value.universal_translator.quality." + quality.configName())));
    }

    /**
     * Applies the quality mode and the custom prompt to the running configuration without closing
     * the screen.
     *
     * <p>The provider is rebuilt through exactly the path Save uses, so the new prompt is used by
     * the next request and the cache key moves with it. A failure leaves the previous configuration
     * running and reports it on the engine tab's own status line.
     */
    private void applyPromptSettings() {
        refreshQualityLabel();
        try {
            String prompt = customPrompt == null
                    ? original.customSystemPrompt()
                    : TranslationPrompt.fromEditorText(customPrompt.getValue());
            ForgeConfig updated = original.withPromptSettings(quality, prompt);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            ForgeTranslationRuntime.initialize(updated);
            updated.save();
            setTestStatus(tr("screen.universal_translator.diagnostics.settings_saved"), false);
        } catch (Exception exception) {
            setTestStatus(tr("screen.universal_translator.diagnostics.settings_failed",
                    describe(exception)), true);
        }
    }

    private void updateTabVisibility() {
        boolean isGeneral = activeTab == Tab.GENERAL;
        boolean isScopes = activeTab == Tab.SCOPES;
        boolean isEngine = activeTab == Tab.ENGINE;
        boolean isOutgoing = activeTab == Tab.OUTGOING;
        boolean isHud = activeTab == Tab.HUD;

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
        llmConfigButton.visible = isEngine && llm;
        modelPickButton.visible = isEngine && llm;
        fetchModelsButton.visible = isEngine && llm;
        // The endpoint box edits the LibreTranslate endpoint key. Showing it for every online
        // provider let a DeepSeek user edit a value DeepSeek never reads.
        endpoint.visible = isEngine && isLibreTranslate();
        testConnectionButton.visible = isEngine && llm;
        checkSettingsButton.visible = isEngine && !offline && !llm;
        qualityButton.visible = isEngine;
        customPrompt.visible = isEngine;
        applyPromptButton.visible = isEngine;
        if (!isEngine) {
            modelListOpen = false;
        }

        // Tab 4: Outgoing
        outgoingButton.visible = isOutgoing;
        outgoingTargetLanguageButton.visible = isOutgoing;

        // Tab 5: HUD
        hudIndicatorButton.visible = isHud;
        hudCornerButton.visible = isHud;
        hudSizeButton.visible = isHud;
        hudMarginButton.visible = isHud;
        hudColorButton.visible = isHud;
        hudContentButton.visible = isHud;
        hudVisibilityButton.visible = isHud;

        refreshTabButtons();
    }

    private void refreshTabButtons() {
        if (tabGeneralButton == null) return;
        tabGeneralButton.setMessage(tabTitle("screen.universal_translator.tab.general", activeTab == Tab.GENERAL));
        tabScopesButton.setMessage(tabTitle("screen.universal_translator.tab.scopes", activeTab == Tab.SCOPES));
        tabEngineButton.setMessage(tabTitle("screen.universal_translator.tab.engine", activeTab == Tab.ENGINE));
        tabOutgoingButton.setMessage(tabTitle("screen.universal_translator.tab.outgoing", activeTab == Tab.OUTGOING));
        tabHudButton.setMessage(tabTitle("screen.universal_translator.tab.hud", activeTab == Tab.HUD));
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
                    animatedUi,
                    new HudIndicatorSettings(hudIndicator, hudIndicatorCorner,
                            hudIndicatorSize, hudIndicatorMargin, hudIndicatorColor,
                            hudIndicatorContent, hudIndicatorVisibility,
                            hudIndicatorOffsetX, hudIndicatorOffsetY))
                    // Save writes the engine tab's prompt controls too, so applying the prompt
                    // and then saving cannot silently restore the value the screen
                    // was opened with.
                    .withPromptSettings(quality, TranslationPrompt.fromEditorText(
                            customPrompt == null ? "" : customPrompt.getValue()));
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
        } else if (!testStatus.isEmpty()) {
            graphics.centered(new StringTextComponent(testStatus), width / 2, messageY,
                    testStatusIsError ? 0xFFFF5555 : 0xFF55FF55);
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
            }
        }
        if (activeTab == Tab.ENGINE) {
            renderEngineInfo(graphics);
        }
        return opening;
    }

    protected final void renderSettingsAfter(ForgeScreenCanvas graphics, int mouseX, int mouseY,
                                              float opening) {
        renderHudDragPreview(graphics);
        if (animatedUi) {
            int overlayAlpha = SettingsUiAnimation.openingOverlayAlpha(opening);
            if (overlayAlpha > 0) {
                graphics.fill(0, 0, width, height, overlayAlpha << 24);
            }
        }
        if (activeTab == Tab.ENGINE && modelListOpen) {
            // The catalog is a modal overlay: it covers the widgets and every other overlay.
            renderModelList(graphics, mouseX, mouseY);
            return;
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
        if (activeTab == Tab.ENGINE && modelListOpen) {
            handleModelListClick(mouseX, mouseY);
            return true;
        }
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
                fetchedModels = Collections.emptyList();
                modelListOpen = false;
                setTestStatus("", false);
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

    /**
     * Top of the engine tab's control block: three control rows plus the read-only summary and the
     * privacy hint, centred in the band between the tab bar and Save. The shared geometry is left
     * alone because Save/Cancel and the HUD preview depend on it.
     */
    private int engineBlockTop() {
        Layout layout = layout();
        int blockHeight = layout.contentRowStep * 4 + 20 + 46;
        int available = layout.saveY - 6 - layout.contentTop;
        return layout.contentTop + Math.max(0, (available - blockHeight) / 2);
    }

    private int engineRow(int index) {
        return engineBlockTop() + layout().contentRowStep * index;
    }

    private boolean isLibreTranslate() {
        return LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider);
    }

    /** Read-only engine facts plus the always-visible privacy hint. */
    private void renderEngineInfo(ForgeScreenCanvas graphics) {
        int y = engineRow(4) + 24;
        // The prompt field carries no placeholder text on this version, so its hint is drawn as a
        // label just above it, the same way the LLM screen labels its fields.
        graphics.text(new TranslationTextComponent("screen.universal_translator.diagnostics.prompt_hint"),
                layout().left, engineRow(4) - 11, 0xFFA0A0A0);
        if (isLlm()) {
            graphics.centered(new TranslationTextComponent(
                            "screen.universal_translator.engine.summary",
                            trimForDisplay(llmEndpoint),
                            tr(llmApiKey == null || llmApiKey.isEmpty()
                                    ? "screen.universal_translator.engine.key_unset"
                                    : "screen.universal_translator.engine.key_set")),
                    width / 2, y, 0xFFA0A0A0);
        } else if (!isOffline() && !isLibreTranslate()) {
            graphics.centered(new TranslationTextComponent(
                            "screen.universal_translator.engine.config_file"),
                    width / 2, y, 0xFFA0A0A0);
        }
        graphics.centered(new TranslationTextComponent(isOffline()
                        ? "screen.universal_translator.info.offline"
                        : "screen.universal_translator.info.api"),
                width / 2, y + 18, 0xFFFFAA55);
    }

    /** Queries the configured LLM endpoint for its catalog and opens the picker. */
    private void fetchModels() {
        if (fetchingModels) {
            return;
        }
        final String endpointValue = llmEndpoint;
        final String keyValue = llmApiKey;
        fetchingModels = true;
        setTestStatus(tr("screen.universal_translator.llm.fetching"), false);
        Thread worker = new Thread(() -> {
            OpenAiModelCatalog.Catalog catalog = null;
            String failure = "";
            try {
                catalog = OpenAiModelCatalog.fetchCatalog(provider, endpointValue, keyValue);
            } catch (Exception error) {
                failure = describe(error);
            }
            fetchingModels = false;
            if (!failure.isEmpty()) {
                setTestStatus(tr("screen.universal_translator.llm.fetch_failed", failure), true);
                return;
            }
            if (!catalog.jsonBody()) {
                setTestStatus(tr("screen.universal_translator.engine.test_not_json"), true);
                return;
            }
            if (catalog.models().isEmpty()) {
                setTestStatus(tr("screen.universal_translator.llm.fetch_empty"), true);
                return;
            }
            fetchedModels = catalog.models();
            modelPage = 0;
            modelListOpen = true;
            setTestStatus("", false);
        }, "universal-translator-model-list");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Checks that the selected service is reachable: an LLM endpoint must answer with a model
     * catalog, LibreTranslate must answer on its languages endpoint, and every other service is
     * only checked for complete credentials because the screen owns no endpoint for it.
     */
    private void testConnection() {
        if (testing) {
            return;
        }
        final boolean llm = isLlm();
        final boolean libre = isLibreTranslate();
        final String endpointValue = llm ? llmEndpoint : endpoint.getValue().trim();
        final String keyValue = llmApiKey;
        testing = true;
        setTestStatus(tr("screen.universal_translator.engine.testing"), false);
        Thread worker = new Thread(() -> {
            String message;
            boolean error;
            try {
                if (llm) {
                    // Probe the endpoint translations actually use. Reading /models can succeed
                    // while every translation fails on a wrong chat path or an unset model.
                    String model = llmModel == null ? "" : llmModel.trim();
                    if (model.isEmpty()) {
                        message = tr("screen.universal_translator.engine.test_need_model");
                        error = true;
                    } else {
                        ProviderProbe.probe(provider, endpointValue, keyValue, model);
                        message = tr("screen.universal_translator.engine.test_ok_probe", model);
                        error = false;
                    }
                } else if (libre) {
                    String url = libreLanguagesUrl(endpointValue);
                    EndpointPolicy.requireSafeEndpoint(url);
                    String body = new HttpJsonClient(5000, 15000).get(URI.create(url),
                            Collections.<String, String>emptyMap());
                    // The languages catalog is JSON; anything else means this is not LibreTranslate.
                    JsonStrings.parse(body);
                    message = tr("screen.universal_translator.engine.test_ok_libre");
                    error = false;
                } else {
                    original.validateProviderConfiguration();
                    message = tr("screen.universal_translator.engine.test_ok_config");
                    error = false;
                }
            } catch (Exception failure) {
                message = describe(failure);
                error = true;
            }
            testing = false;
            setTestStatus(message, error);
        }, "universal-translator-connection-test");
        worker.setDaemon(true);
        worker.start();
    }

    private void setTestStatus(String message, boolean error) {
        testStatus = message;
        testStatusIsError = error;
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    /** LibreTranslate exposes reachability on {@code /languages}, not on {@code /translate}. */
    private static String libreLanguagesUrl(String endpoint) {
        String base = endpoint == null ? "" : endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(TRANSLATE_PATH)) {
            base = base.substring(0, base.length() - TRANSLATE_PATH.length());
        }
        return base + LANGUAGES_PATH;
    }

    /** Keeps the read-only endpoint line inside the panel; the editor still holds the full value. */
    private static String trimForDisplay(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= 44 ? trimmed : "\u2026" + trimmed.substring(trimmed.length() - 43);
    }

    /** Draws the fetched catalog over the settings so a served model can be picked. */
    private void renderModelList(ForgeScreenCanvas graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, height, 0xFF101010);
        graphics.centered(new TranslationTextComponent(
                        "screen.universal_translator.llm.select_model", fetchedModels.size()),
                width / 2, 20, 0xFFFFFFFF);
        int rows = modelRowsPerPage();
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < fetchedModels.size(); row++) {
            int rowY = MODEL_LIST_TOP + row * MODEL_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + MODEL_ROW_HEIGHT;
            graphics.text(new StringTextComponent((hovered ? "> " : "  ") + fetchedModels.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = modelPageCount(rows);
        graphics.centered(new StringTextComponent("< " + (modelPage + 1) + "/" + pages + " >"),
                width / 2, height - 46, 0xFFFFFFFF);
        graphics.centered(new TranslationTextComponent("screen.universal_translator.llm.select_back"),
                width / 2, height - 30, 0xFFFFD060);
        graphics.centered(new TranslationTextComponent("screen.universal_translator.llm.select_hint"),
                width / 2, height - 16, 0xFFA0A0A0);
    }

    private void handleModelListClick(double mouseX, double mouseY) {
        if (mouseY >= height - 54 && mouseY < height - 38) {
            int pages = modelPageCount(modelRowsPerPage());
            if (pages > 1) {
                modelPage = mouseX < width / 2.0
                        ? (modelPage + pages - 1) % pages : (modelPage + 1) % pages;
            }
            return;
        }
        if (mouseY >= height - 38 && mouseY < height - 20) {
            modelListOpen = false;
            return;
        }
        if (mouseY < MODEL_LIST_TOP) {
            return;
        }
        int rows = modelRowsPerPage();
        int row = (int) ((mouseY - MODEL_LIST_TOP) / MODEL_ROW_HEIGHT);
        int index = modelPage * rows + row;
        if (row >= rows || index >= fetchedModels.size()) {
            return;
        }
        llmModel = fetchedModels.get(index);
        modelListOpen = false;
        refreshLabels();
    }

    private int modelRowsPerPage() {
        return Math.max(3, (height - MODEL_LIST_TOP - MODEL_LIST_FOOTER_HEIGHT) / MODEL_ROW_HEIGHT);
    }

    private int modelPageCount(int rows) {
        return Math.max(1, (fetchedModels.size() + rows - 1) / rows);
    }

    /**
     * Starts a HUD indicator drag when the press lands on the preview handle.
     *
     * <p>Version-neutral seam, exactly like {@link #handleSelectionClick(double, double)}: this base
     * class never mentions a Minecraft mouse signature, and the per-version screens call it from
     * their own {@code mouseClicked} bridge.
     *
     * <p>Refuses while a selection list is open so the list keeps first claim on the click and the
     * pre-existing click handling is unchanged even though the screens now ask for a drag first.
     */
    protected boolean handleDragStart(double mouseX, double mouseY) {
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            return false;
        }
        if (activeTab != Tab.HUD) {
            return false;
        }
        if (!insideHudDragHandle(mouseX, mouseY)) {
            return false;
        }
        hudDragging = true;
        hudDragLastX = mouseX;
        hudDragLastY = mouseY;
        return true;
    }

    /** Moves the pending offsets by the pointer delta, clamped to the frozen core bounds. */
    protected boolean handleDragMove(double mouseX, double mouseY) {
        if (!hudDragging) {
            return false;
        }
        // One preview pixel is not one GUI pixel: the panel is a scaled-down screen, so undo the
        // scale before turning the pointer delta into a real offset.
        hudIndicatorOffsetX = clampOffset(hudIndicatorOffsetX
                + (int) Math.round((mouseX - hudDragLastX) / hudScaleX()));
        hudIndicatorOffsetY = clampOffset(hudIndicatorOffsetY
                + (int) Math.round((mouseY - hudDragLastY) / hudScaleY()));
        hudDragLastX = mouseX;
        hudDragLastY = mouseY;
        return true;
    }

    /** Ends the drag. The offsets are only persisted later by {@link #saveAndApply()}. */
    protected boolean handleDragEnd() {
        if (!hudDragging) {
            return false;
        }
        hudDragging = false;
        return true;
    }

    private static int clampOffset(int value) {
        return Math.max(HudIndicatorSettings.MIN_OFFSET,
                Math.min(HudIndicatorSettings.MAX_OFFSET, value));
    }

    private int hudPreviewX() {
        return layout().left;
    }

    private int hudPreviewY() {
        return layout().contentRow(4);
    }

    private int hudPreviewW() {
        return layout().totalWidth;
    }

    /**
     * Panel height, capped by the space above the Save/Cancel row.
     *
     * <p>{@code contentRow(4)} does not shrink on very short windows because the row step has a
     * floor, so a fixed height would overlap Save. Clamping here keeps the panel clear of it.
     */
    private int hudPreviewH() {
        int available = layout().saveY - 4 - hudPreviewY();
        if (available < HUD_DRAG_HANDLE_SIZE) {
            // No room above Save: skip the preview entirely rather than draw a panel that
            // overlaps it, which is exactly what the cap above exists to prevent.
            return 0;
        }
        return Math.min(HUD_DRAG_PREVIEW_HEIGHT, available);
    }

    /**
     * Where the indicator sits on the real screen before the drag offset: the same anchor maths
     * the HUD mixin uses, so the preview cannot drift from what the player actually sees.
     */
    private int hudAnchorX() {
        return hudIndicatorCorner.isRight()
                ? HUD_PREVIEW_SCREEN_WIDTH - hudIndicatorMargin - hudIndicatorSize
                : hudIndicatorMargin;
    }

    private int hudAnchorY() {
        return hudIndicatorCorner.isBottom()
                ? HUD_PREVIEW_SCREEN_HEIGHT - hudIndicatorMargin - hudIndicatorSize
                : hudIndicatorMargin;
    }

    private double hudScaleX() {
        return hudPreviewW() / (double) HUD_PREVIEW_SCREEN_WIDTH;
    }

    private double hudScaleY() {
        return hudPreviewH() / (double) HUD_PREVIEW_SCREEN_HEIGHT;
    }

    private int hudHandleX() {
        int px = hudPreviewX();
        int pw = hudPreviewW();
        int x = px + (int) Math.round((hudAnchorX() + hudIndicatorOffsetX) * hudScaleX());
        return Math.max(px, Math.min(px + pw - HUD_DRAG_HANDLE_SIZE, x));
    }

    private int hudHandleY() {
        int py = hudPreviewY();
        int ph = hudPreviewH();
        int y = py + (int) Math.round((hudAnchorY() + hudIndicatorOffsetY) * hudScaleY());
        return Math.max(py, Math.min(py + ph - HUD_DRAG_HANDLE_SIZE, y));
    }

    private boolean insideHudDragHandle(double mouseX, double mouseY) {
        if (hudPreviewH() <= 0) {
            return false;
        }
        int hx = hudHandleX();
        int hy = hudHandleY();
        return mouseX >= hx && mouseX < hx + HUD_DRAG_HANDLE_SIZE
                && mouseY >= hy && mouseY < hy + HUD_DRAG_HANDLE_SIZE;
    }

    /**
     * Draws the HUD indicator preview strip plus its drag handle.
     *
     * <p>Called from {@link #renderSettingsAfter} so it lands after {@code super.render}; the
     * selection overlay is drawn afterwards and therefore still covers it.
     */
    private void renderHudDragPreview(ForgeScreenCanvas graphics) {
        if (activeTab != Tab.HUD) {
            return;
        }
        int px = hudPreviewX();
        int py = hudPreviewY();
        int pw = hudPreviewW();
        int ph = hudPreviewH();
        if (ph <= 0) {
            return;
        }
        graphics.fill(px, py, px + pw, py + ph, 0x40000000);
        int hx = hudHandleX();
        int hy = hudHandleY();
        graphics.fill(hx, hy, hx + HUD_DRAG_HANDLE_SIZE, hy + HUD_DRAG_HANDLE_SIZE,
                hudDragging ? 0xFFFFAA00 : 0xFF00AA00);
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

    private static String cornerLabel(HudIndicatorCorner corner) {
        return tr("value.universal_translator.hud_corner." + corner.configName().replace('-', '_'));
    }

    private static String hudColorLabel(HudIndicatorColor color) {
        return tr("value.universal_translator.color." + color.configName().replace('-', '_'));
    }

    private static String hudContentLabel(HudIndicatorContent content) {
        return tr("value.universal_translator.hud_content." + content.configName().replace('-', '_'));
    }

    private static String hudVisibilityLabel(HudIndicatorVisibility visibility) {
        return tr("value.universal_translator.hud_visibility." + visibility.configName().replace('-', '_'));
    }

    private static String tr(String key, Object... arguments) {
        return new TranslationTextComponent(key, arguments).getString();
    }

    private Layout layout() {
        SettingsScreenLayout.Geometry geometry = SettingsScreenLayout.calculate(width, height, 5);
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
    /** Vertical distance between two text lines. */
    private static final int LINE_STEP = 17;
    /** First line's y, just under the title. */
    private static final int LINE_START = 43;

    private final Screen parent;
    private final ForgeConfig config;
    private Button debugButton;
    private Button pageButton;
    private Button resetButton;
    /** Whether the counters page is shown instead of the engine diagnostics page. */
    private boolean statisticsPage;
    private boolean debugEnabled;
    private String exportStatus = "";
    private boolean exportFailed;

    UniversalTranslatorDiagnosticsScreenBase(Screen parent, ForgeConfig config) {
        super(new TranslationTextComponent("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected final void init() {
        ForgeConfig live = liveConfig();
        if (live != null) {
            // Re-read here rather than in the constructor so a rebuild after a resize, or a change
            // made by another screen, is reflected instead of showing a stale value.
            debugEnabled = live.debugLog();
        }
        int totalWidth = Math.max(180, Math.min(320, width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;


        debugButton = addButton(new Button(left, height - 76, buttonWidth, 20,
                new StringTextComponent(""), button -> {
                    debugEnabled = !debugEnabled;
                    applyDebugMode();
                }));
        addButton(new Button(left + buttonWidth + gap, height - 76, buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.debug.bundle"),
                button -> exportBundle()));

        pageButton = addButton(new Button(left, height - 52, buttonWidth, 20,
                new StringTextComponent(""), button -> {
                    statisticsPage = !statisticsPage;
                    refreshPageLabels();
                }));
        resetButton = addButton(new Button(
                left + buttonWidth + gap, height - 52, buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.stats.reset"),
                button -> resetStatistics()));

        addButton(new Button(left, height - 28, buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.diagnostics.back"), button -> onClose()));
        addButton(new Button(left + buttonWidth + gap, height - 28, buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.diagnostics.export"), button -> exportLog()));
        refreshDebugLabel();
        refreshPageLabels();
    }

    /**
     * The configuration the controls read and write.
     *
     * <p>The runtime's copy is preferred so a screen rebuilt after a resize, or a change made by
     * another screen, is picked up; the configuration handed to the constructor is the fallback for
     * the window before the first load.
     */
    private ForgeConfig liveConfig() {
        ForgeConfig live = ForgeTranslationRuntime.diagnosticsConfig();
        return live == null ? config : live;
    }

    private void refreshDebugLabel() {
        if (debugButton == null) {
            return;
        }
        debugButton.setMessage(new TranslationTextComponent("screen.universal_translator.debug.mode",
                tr(debugEnabled
                        ? "value.universal_translator.on" : "value.universal_translator.off")));
    }

    private void refreshPageLabels() {
        if (pageButton != null) {
            pageButton.setMessage(new TranslationTextComponent(statisticsPage
                    ? "screen.universal_translator.stats.show_diagnostics"
                    : "screen.universal_translator.stats.show"));
        }
        if (resetButton != null) {
            // Only the counters page can be cleared, so the button says so by going flat.
            resetButton.active = statisticsPage;
        }
    }

    /**
     * Switches the request trace on or off and persists the switch.
     *
     * <p>{@link DebugLog} is reconfigured directly because the switch takes effect immediately; the
     * configuration write only makes it survive the next launch.
     */
    private void applyDebugMode() {
        refreshDebugLabel();
        ForgeConfig live = liveConfig();
        if (live == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            ForgeConfig updated = live.withDebugLog(debugEnabled);
            ForgeTranslationRuntime.initialize(updated);
            updated.save();
            Path configFile = updated.configFile();
            DebugLog.global().configure(
                    configFile.resolveSibling(DebugLog.FILE_NAME), debugEnabled);
            exportStatus = tr("screen.universal_translator.debug.changed",
                    tr(debugEnabled
                            ? "value.universal_translator.on" : "value.universal_translator.off"));
            exportFailed = false;
        } catch (Exception failure) {
            exportStatus = settingsFailure(failure);
            exportFailed = true;
        }
    }

    /** Clears the process-wide counters and writes the cleared values out. */
    private void resetStatistics() {
        TranslationStats.global().reset();
        exportStatus = tr("screen.universal_translator.stats.reset_done");
        exportFailed = false;
    }

    private String settingsFailure(Exception failure) {
        String message = failure.getMessage();
        return tr("screen.universal_translator.diagnostics.settings_failed",
                message == null || message.trim().isEmpty()
                        ? failure.getClass().getSimpleName() : message);
    }

    protected final void renderDiagnostics(ForgeScreenCanvas graphics) {
        graphics.fill(0, 0, width, height, 0xE510151C);
        graphics.fill(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        graphics.centered(statisticsPage
                        ? new TranslationTextComponent("screen.universal_translator.stats.title")
                        : title,
                width / 2, 18, 0xFFFFFFFF);
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        List<String> lines = displayLines();
        // Stop before the status line and the button rows. A short window shows fewer lines instead
        // of drawing text on top of a button, and the last visible line says that it was cut.
        int limit = Math.max(1, (height - 96 - LINE_START) / LINE_STEP + 1);
        int shown = Math.min(lines.size(), limit);
        int y = LINE_START;
        for (int index = 0; index < shown; index++) {
            String line = lines.get(index);
            if (index == shown - 1 && shown < lines.size()) {
                line = line + " ...";
            }
            graphics.text(new StringTextComponent(line), left, y, 0xFFD0D0D0);
            y += LINE_STEP;
        }
        graphics.centered(new TranslationTextComponent(statisticsPage
                        ? "screen.universal_translator.stats.note"
                        : "screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 88), 0xFF909090);
        if (!exportStatus.isEmpty()) {
            graphics.centered(new StringTextComponent(exportStatus), width / 2, height - 82,
                    exportFailed ? 0xFFFF5555 : 0xFF55FF88);
        }
    }

    /** The lines the current page shows. */
    private List<String> displayLines() {
        try {
            return statisticsPage
                    ? TranslationStats.global().localizedLines(
                            UniversalTranslatorDiagnosticsScreenBase::tr)
                    : safeLines();
        } catch (RuntimeException ignored) {
            return Collections.singletonList(
                    tr("screen.universal_translator.diagnostics.unavailable"));
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

    /**
     * Writes one archive holding the diagnostics, the debug log, the redacted configuration and the
     * environment into the configuration directory.
     */
    private void exportBundle() {
        ForgeConfig live = liveConfig();
        if (live == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            Path configFile = live.configFile();
            Path archive = DiagnosticsBundleExporter.export(
                    configFile.getParent(), configFile,
                    configFile.resolveSibling(DebugLog.FILE_NAME), "forge", safeLines());
            exportStatus = tr("screen.universal_translator.debug.bundle_exported",
                    archive.getFileName().toString());
            exportFailed = false;
        } catch (Exception failure) {
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
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = 12;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = 46;
    /** Room kept under the rows for the paging, back, and hint lines. */
    private static final int LIST_FOOTER_HEIGHT = 62;
    private static final int ESCAPE_KEY = 256;

    private final UniversalTranslatorConfigScreenBase parent;
    private final String initialEndpoint;
    private final String initialModel;
    private final boolean hasStoredKey;
    private TextFieldWidget endpoint;
    private TextFieldWidget model;
    private TextFieldWidget apiKey;
    private volatile String status = "";
    private volatile boolean statusIsError;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean fetching;
    private boolean modelListOpen;
    private int modelPage;
    /** Canvas captured by renderLlm so the catalog overlay can be drawn over the widgets. */
    private ForgeScreenCanvas canvas;

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
        int top = Math.max(42, (height - 174) / 2);
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
        addButton(new Button(left, top + 132, formWidth, 20,
                new TranslationTextComponent("screen.universal_translator.llm.fetch_models"),
                button -> fetchModels()));
    }

    protected final void renderLlm(ForgeScreenCanvas graphics) {
        canvas = graphics;
        int formWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - formWidth) / 2;
        int top = Math.max(42, (height - 174) / 2);
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
            graphics.centered(new StringTextComponent(status), width / 2, top + 158,
                    statusIsError ? 0xFFFF5555 : 0xFFE0E0E0);
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

    /** Asks the configured endpoint for its model catalog without blocking the render thread. */
    private void fetchModels() {
        if (fetching) {
            return;
        }
        final String endpointValue = endpoint.getValue().trim();
        if (endpointValue.isEmpty()) {
            setStatus(tr("error.universal_translator.llm_required"), true);
            return;
        }
        String enteredKey = apiKey.getValue().trim();
        final String keyValue = enteredKey.isEmpty()
                ? parent.llmApiKey() : ("-".equals(enteredKey) ? "" : enteredKey);
        fetching = true;
        setStatus(tr("screen.universal_translator.llm.fetching"), false);
        Thread worker = new Thread(() -> {
            List<String> models = Collections.emptyList();
            String failure = "";
            try {
                models = OpenAiModelCatalog.fetch(endpointValue, keyValue);
            } catch (Exception error) {
                failure = describe(error);
            }
            fetching = false;
            if (!failure.isEmpty()) {
                setStatus(tr("screen.universal_translator.llm.fetch_failed", failure), true);
                return;
            }
            if (models.isEmpty()) {
                setStatus(tr("screen.universal_translator.llm.fetch_empty"), true);
                return;
            }
            fetchedModels = models;
            modelPage = 0;
            modelListOpen = true;
            setStatus("", false);
        }, "universal-translator-model-list");
        worker.setDaemon(true);
        worker.start();
    }

    private void setStatus(String message, boolean error) {
        status = message;
        statusIsError = error;
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void render(MatrixStack pose, int mouseX, int mouseY, float delta) {
        super.render(pose, mouseX, mouseY, delta);
        if (modelListOpen && canvas != null) {
            renderModelList(canvas, mouseX, mouseY);
        }
    }

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(ForgeScreenCanvas graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, height, 0xFF101010);
        graphics.centered(new TranslationTextComponent("screen.universal_translator.llm.select_model",
                fetchedModels.size()), width / 2, 20, 0xFFFFFFFF);
        int rows = rowsPerPage();
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < fetchedModels.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            graphics.text(new StringTextComponent((hovered ? "> " : "  ") + fetchedModels.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = pageCount(rows);
        graphics.centered(new StringTextComponent("< " + (modelPage + 1) + "/" + pages + " >"),
                width / 2, height - 46, 0xFFFFFFFF);
        graphics.centered(new TranslationTextComponent("screen.universal_translator.llm.select_back"),
                width / 2, height - 30, 0xFFFFD060);
        graphics.centered(new TranslationTextComponent("screen.universal_translator.llm.select_hint"),
                width / 2, height - 16, 0xFFA0A0A0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (modelListOpen) {
            handleModelListClick(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleModelListClick(double mouseX, double mouseY) {
        if (mouseY >= height - 54 && mouseY < height - 38) {
            int pages = pageCount(rowsPerPage());
            if (pages > 1) {
                modelPage = mouseX < width / 2.0
                        ? (modelPage + pages - 1) % pages : (modelPage + 1) % pages;
            }
            return;
        }
        if (mouseY >= height - 38 && mouseY < height - 20) {
            modelListOpen = false;
            return;
        }
        if (mouseY < LIST_TOP) {
            return;
        }
        int rows = rowsPerPage();
        int row = (int) ((mouseY - LIST_TOP) / LIST_ROW_HEIGHT);
        int index = modelPage * rows + row;
        if (row >= rows || index >= fetchedModels.size()) {
            return;
        }
        model.setValue(fetchedModels.get(index));
        modelListOpen = false;
        setStatus("", false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (modelListOpen) {
            if (keyCode == ESCAPE_KEY) {
                modelListOpen = false;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return modelListOpen || super.charTyped(chr, modifiers);
    }

    private int rowsPerPage() {
        return Math.max(3, (height - LIST_TOP - LIST_FOOTER_HEIGHT) / LIST_ROW_HEIGHT);
    }

    private int pageCount(int rows) {
        return Math.max(1, (fetchedModels.size() + rows - 1) / rows);
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

interface ForgeScreenCanvas {
    void fill(int left, int top, int right, int bottom, int color);
    void centered(ITextComponent text, int centerX, int y, int color);
    void text(ITextComponent text, int x, int y, int color);
}
