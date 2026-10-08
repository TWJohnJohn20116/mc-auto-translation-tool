package org.universaltranslator.fabric;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationProviderCatalog;
import org.universaltranslator.core.SettingsUiAnimation;
import org.universaltranslator.core.SettingsScreenLayout;
import org.universaltranslator.core.SettingsSelectionList;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.provider.OpenAiChatTranslationProvider;
import org.universaltranslator.core.provider.OpenAiModelCatalog;

/** Minimal dependency-free settings screen, opened with U by default. */
final class UniversalTranslatorConfigScreen extends Screen {

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
    private static final String LIBRETRANSLATE_PROVIDER = "libretranslate";
    private static final String TRANSLATE_PATH = "/translate";
    private static final String LANGUAGES_PATH = "/languages";

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
    private boolean hudIndicator;
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



    private enum Tab {
        GENERAL,
        SCOPES,
        ENGINE,
        OUTGOING,
        HUD
    }
    private Tab activeTab = Tab.GENERAL;
    private ButtonWidget tabGeneralButton;
    private ButtonWidget tabScopesButton;
    private ButtonWidget tabEngineButton;
    private ButtonWidget tabOutgoingButton;
    private ButtonWidget tabHudButton;
    private ButtonWidget llmConfigButton;
    private ButtonWidget modelPickButton;
    private ButtonWidget fetchModelsButton;
    private ButtonWidget testConnectionButton;
    private ButtonWidget checkSettingsButton;
    private ButtonWidget hudIndicatorButton;
    private ButtonWidget hudCornerButton;
    private ButtonWidget hudSizeButton;
    private ButtonWidget hudMarginButton;
    private ButtonWidget hudColorButton;
    private ButtonWidget hudContentButton;
    private ButtonWidget hudVisibilityButton;



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

    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
        super(Text.translatable("screen.universal_translator.settings.title"));
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
        this.hudIndicator = config.hudIndicator;
        this.hudIndicatorCorner = config.hudIndicatorCorner;
        this.hudIndicatorSize = config.hudIndicatorSize;
        this.hudIndicatorMargin = config.hudIndicatorMargin;
        this.hudIndicatorColor = config.hudIndicatorColor;
        this.hudIndicatorContent = config.hudIndicatorContent;
        this.hudIndicatorVisibility = config.hudIndicatorVisibility;
        this.hudIndicatorOffsetX = config.hudIndicatorOffsetX;
        this.hudIndicatorOffsetY = config.hudIndicatorOffsetY;


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
        this.tabGeneralButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            activeTab = Tab.GENERAL;
            updateTabVisibility();
        }).dimensions(layout.tabX(0), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabScopesButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            activeTab = Tab.SCOPES;
            updateTabVisibility();
        }).dimensions(layout.tabX(1), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabEngineButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            activeTab = Tab.ENGINE;
            updateTabVisibility();
        }).dimensions(layout.tabX(2), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabOutgoingButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            activeTab = Tab.OUTGOING;
            updateTabVisibility();
        }).dimensions(layout.tabX(3), layout.tabY(), layout.tabWidth(), 20).build());
        this.tabHudButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            activeTab = Tab.HUD;
            updateTabVisibility();
        }).dimensions(layout.tabX(4), layout.tabY(), layout.tabWidth(), 20).build());

        // --- Tab 1: General (常規) ---
        this.enabledButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            enabled = !enabled;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.targetLanguageButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE;
        }).dimensions(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.displayButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
            refreshLabels();
        }).dimensions(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.colorButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translatedTextColor = translatedTextColor.next();
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.cacheButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).dimensions(left, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.uiStyleButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.diagnosticsButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.universal_translator.diagnostics.title"), button -> {
            if (client != null) {
                client.setScreen(new UniversalTranslatorDiagnosticsScreen(this));
            }
        }).dimensions(left, layout.contentRow(3), layout.totalWidth, 20).build());

        // --- Tab 2: Scopes (範圍) ---
        this.chatButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.otherButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.vanillaButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateVanilla = !translateVanilla;
            refreshLabels();
        }).dimensions(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.playerNamesButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translatePlayerNames = !translatePlayerNames;
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.mixedTextButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateEnglishOnly = !translateEnglishOnly;
            refreshLabels();
        }).dimensions(left, layout.contentRow(2), layout.totalWidth, 20).build());

        this.blockedKeywords = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, layout.contentRow(3), layout.totalWidth, 20,
                Text.translatable("screen.universal_translator.blocked_keywords")));
        this.blockedKeywords.setMaxLength(4096);
        this.blockedKeywords.setText(blockedKeywordsValue);
        this.blockedKeywords.setSuggestion(tr("screen.universal_translator.blocked_keywords_hint"));

        // --- Tab 3: Engine (引擎) ---
        // The engine rows are centred in the free band (see engineRow) so the tab does not sit in
        // the top third of an otherwise empty panel, and every row is reachable without moving the
        // shared Save/Cancel row that the other tabs and the HUD preview depend on.
        this.providerButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            openSelection = SettingsSelectionList.Kind.PROVIDER;
        }).dimensions(left, engineRow(0), layout.totalWidth, 20).build());

        this.modelButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            offlineModel = offlineModel.next();
            refreshLabels();
        }).dimensions(left, engineRow(1), layout.buttonWidth, 20).build());

        this.downloadButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            offlineAutoDownload = !offlineAutoDownload;
            refreshLabels();
        }).dimensions(layout.right, engineRow(1), layout.buttonWidth, 20).build());

        this.modelPickButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            if (fetchedModels.isEmpty()) {
                fetchModels();
            } else {
                modelListOpen = true;
            }
        }).dimensions(left, engineRow(1), layout.buttonWidth, 20).build());

        this.fetchModelsButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.universal_translator.llm.fetch_models"), button -> fetchModels())
                .dimensions(layout.right, engineRow(1), layout.buttonWidth, 20).build());

        this.llmConfigButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.universal_translator.option.llm_settings"), button -> {
            if (this.client != null) {
                this.client.setScreen(new UniversalTranslatorLlmConfigScreen(
                        this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
            }
        }).dimensions(left, engineRow(2), layout.buttonWidth, 20).build());

        this.testConnectionButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> testConnection())
                .dimensions(layout.right, engineRow(2), layout.buttonWidth, 20).build());

        this.checkSettingsButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> testConnection())
                .dimensions(left, engineRow(2), layout.totalWidth, 20).build());

        this.fallbackButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).dimensions(left, engineRow(2), layout.totalWidth, 20).build());

        this.endpoint = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, engineRow(1), layout.totalWidth, 20,
                Text.translatable("screen.universal_translator.endpoint")));
        this.endpoint.setMaxLength(512);
        this.endpoint.setText(endpointValue);

        // --- Tab 4: Outgoing (傳送) ---
        this.outgoingButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateOutgoing = !translateOutgoing;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.totalWidth, 20).build());

        this.outgoingTargetLanguageButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE;
        }).dimensions(left, layout.contentRow(1), layout.totalWidth, 20).build());

        // --- Tab 5: HUD (抬頭顯示) ---
        this.hudIndicatorButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            hudIndicator = !hudIndicator;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.hudCornerButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            hudIndicatorCorner = HudIndicatorCorner.values()[
                    (hudIndicatorCorner.ordinal() + 1) % HudIndicatorCorner.values().length];
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.hudSizeButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            hudIndicatorSize = hudIndicatorSize >= HudIndicatorSettings.MAX_SIZE
                    ? HudIndicatorSettings.MIN_SIZE : hudIndicatorSize + 1;
            refreshLabels();
        }).dimensions(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.hudMarginButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            hudIndicatorMargin = hudIndicatorMargin >= HudIndicatorSettings.MAX_MARGIN
                    ? HudIndicatorSettings.MIN_MARGIN : hudIndicatorMargin + 1;
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.hudColorButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            hudIndicatorColor = HudIndicatorColor.values()[
                    (hudIndicatorColor.ordinal() + 1) % HudIndicatorColor.values().length];
            refreshLabels();
        }).dimensions(left, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.hudContentButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            hudIndicatorContent = HudIndicatorContent.values()[
                    (hudIndicatorContent.ordinal() + 1) % HudIndicatorContent.values().length];
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.hudVisibilityButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            hudIndicatorVisibility = HudIndicatorVisibility.values()[
                    (hudIndicatorVisibility.ordinal() + 1) % HudIndicatorVisibility.values().length];
            refreshLabels();
        }).dimensions(left, layout.contentRow(3), layout.buttonWidth, 20).build());

        // --- Bottom Action Row ---


        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.universal_translator.save"), button -> saveAndApply())
                .dimensions(left, layout.saveY, layout.buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
                .dimensions(layout.right, layout.saveY, layout.buttonWidth, 20).build());

        refreshLabels();
        updateTabVisibility();
    }

    private void refreshLabels() {
        uiStyleButton.setMessage(Text.translatable("screen.universal_translator.option.ui_style",
                tr(animatedUi ? "value.universal_translator.ui_animated"
                        : "value.universal_translator.ui_classic")));
        enabledButton.setMessage(Text.translatable("screen.universal_translator.option.automatic", onOff(enabled)));
        chatButton.setMessage(Text.translatable("screen.universal_translator.option.chat", onOff(translateChat)));
        otherButton.setMessage(Text.translatable("screen.universal_translator.option.other", onOff(translateOther)));
        vanillaButton.setMessage(Text.translatable("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        playerNamesButton.setMessage(Text.translatable(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames)));
        cacheButton.setMessage(Text.translatable("screen.universal_translator.option.cache", onOff(diskCache)));
        providerButton.setMessage(Text.translatable("screen.universal_translator.option.provider", providerLabel()));
        String modelValue = llmModel == null || llmModel.trim().isEmpty()
                ? tr("screen.universal_translator.engine.key_unset") : llmModel.trim();
        modelPickButton.setMessage(Text.translatable("screen.universal_translator.engine.model", modelValue));
        testConnectionButton.setMessage(Text.translatable("screen.universal_translator.engine.test"));
        checkSettingsButton.setMessage(Text.translatable(LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider)
                ? "screen.universal_translator.engine.test"
                : "screen.universal_translator.engine.check"));
        displayButton.setMessage(Text.translatable("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        mixedTextButton.setMessage(Text.translatable("screen.universal_translator.option.mixed", onOff(translateEnglishOnly)));
        colorButton.setMessage(Text.translatable("screen.universal_translator.option.color", colorLabel(translatedTextColor)));
        downloadButton.setMessage(Text.translatable("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        modelButton.setMessage(Text.translatable("screen.universal_translator.option.model", offlineModel.displayName()));
        fallbackButton.setMessage(Text.translatable("screen.universal_translator.option.fallback", onOff(apiFallback)));
        outgoingButton.setMessage(Text.translatable("screen.universal_translator.option.outgoing", onOff(translateOutgoing)));
        targetLanguageButton.setMessage(Text.translatable("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage)));
        outgoingTargetLanguageButton.setMessage(Text.translatable(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage)));
        outgoingTargetLanguageButton.active = translateOutgoing;

        refreshTabButtons();
        hudIndicatorButton.setMessage(Text.translatable(
                "screen.universal_translator.option.hud_indicator", onOff(hudIndicator)));
        hudCornerButton.setMessage(Text.translatable(
                "screen.universal_translator.option.hud_corner", hudCornerLabel(hudIndicatorCorner)));
        hudSizeButton.setMessage(Text.translatable(
                "screen.universal_translator.option.hud_size", hudIndicatorSize));
        hudMarginButton.setMessage(Text.translatable(
                "screen.universal_translator.option.hud_margin", hudIndicatorMargin));
        hudColorButton.setMessage(Text.translatable(
                "screen.universal_translator.option.hud_color", hudColorLabel(hudIndicatorColor)));
        hudContentButton.setMessage(Text.translatable(
                "screen.universal_translator.option.hud_content", hudContentLabel(hudIndicatorContent)));
        hudVisibilityButton.setMessage(Text.translatable(
                "screen.universal_translator.option.hud_visibility", hudVisibilityLabel(hudIndicatorVisibility)));
        hudCornerButton.active = hudIndicator;
        hudSizeButton.active = hudIndicator;
        hudMarginButton.active = hudIndicator;
        hudColorButton.active = hudIndicator;
        hudContentButton.active = hudIndicator;
        hudVisibilityButton.active = hudIndicator;
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
        boolean libre = LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider);
        modelButton.visible = isEngine && offline;
        downloadButton.visible = isEngine && offline;
        fallbackButton.visible = isEngine && offline;
        llmConfigButton.visible = isEngine && llm;
        modelPickButton.visible = isEngine && llm;
        fetchModelsButton.visible = isEngine && llm;
        // The endpoint box edits the LibreTranslate endpoint key. Showing it for every online
        // provider let a DeepSeek user edit a value DeepSeek never reads.
        endpoint.visible = isEngine && libre;
        testConnectionButton.visible = isEngine && llm;
        checkSettingsButton.visible = isEngine && !offline && !llm;
        if (!isEngine) {
            modelListOpen = false;
        }

        // Tab 4: Outgoing
        outgoingButton.visible = isOutgoing;
        outgoingTargetLanguageButton.visible = isOutgoing;

        hudIndicatorButton.visible = isHud;
        hudCornerButton.visible = isHud;
        hudSizeButton.visible = isHud;
        hudMarginButton.visible = isHud;
        hudColorButton.visible = isHud;
        hudContentButton.visible = isHud;
        hudVisibilityButton.visible = isHud;
    }

    private void refreshTabButtons() {
        if (tabGeneralButton == null) return;
        tabGeneralButton.setMessage(tabTitle("screen.universal_translator.tab.general", activeTab == Tab.GENERAL));
        tabScopesButton.setMessage(tabTitle("screen.universal_translator.tab.scopes", activeTab == Tab.SCOPES));
        tabEngineButton.setMessage(tabTitle("screen.universal_translator.tab.engine", activeTab == Tab.ENGINE));
        tabOutgoingButton.setMessage(tabTitle("screen.universal_translator.tab.outgoing", activeTab == Tab.OUTGOING));
        tabHudButton.setMessage(tabTitle("screen.universal_translator.tab.hud", activeTab == Tab.HUD));
    }

    private Text tabTitle(String key, boolean active) {
        String label = tr(key);
        return Text.literal(active ? "§b§l[ " + label + " ]" : "§7" + label);
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
                    animatedUi,
                    new HudIndicatorSettings(hudIndicator, hudIndicatorCorner,
                            hudIndicatorSize, hudIndicatorMargin, hudIndicatorColor,
                            hudIndicatorContent, hudIndicatorVisibility,
                            hudIndicatorOffsetX, hudIndicatorOffsetY));
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
            drawCenteredText(matrices, this.textRenderer, Text.literal(status),
                    this.width / 2, messageY, 0xFFFF5555);
        } else if (!testStatus.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, Text.literal(testStatus),
                    this.width / 2, messageY, testStatusIsError ? 0xFFFF5555 : 0xFF55FF55);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, Text.literal(runtimeStatus),
                    this.width / 2, messageY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFFFF5555 : 0xFF55FF55);
        } else {
            if (activeTab == Tab.OUTGOING) {
                drawCenteredText(matrices, this.textRenderer,
                        Text.translatable("screen.universal_translator.info.outgoing_hint"),
                        this.width / 2, layout.contentRow(2) + 6, 0xFFA0A0A0);
            } else if (activeTab == Tab.GENERAL) {
                drawCenteredText(matrices, this.textRenderer,
                        Text.translatable("screen.universal_translator.info.keybind"),
                        this.width / 2, layout.contentRow(3) + 24, 0xFFA0A0A0);
            }
        }
        if (activeTab == Tab.ENGINE) {
            renderEngineInfo(matrices);
        }
        super.render(matrices, mouseX, mouseY, delta);
        if (animatedUi) {
            int overlayAlpha = SettingsUiAnimation.openingOverlayAlpha(opening);
            if (overlayAlpha > 0) {
                fill(matrices, 0, 0, this.width, this.height, overlayAlpha << 24);
            }
        }
        if (activeTab == Tab.HUD) {
            drawHudDragPreview(matrices);
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            renderSelection(matrices, mouseX, mouseY);
        }
        if (activeTab == Tab.ENGINE && modelListOpen) {
            renderModelList(matrices, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The catalog overlay keeps first claim on the click while it is open.
        if (activeTab == Tab.ENGINE && modelListOpen) {
            handleModelListClick(mouseX, mouseY);
            return true;
        }
        if (openSelection != SettingsSelectionList.Kind.NONE
                && selectFromList(mouseX, mouseY)) {
            return true;
        }
        if (handleDragStart(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double deltaX, double deltaY) {
        if (handleDragMove(mouseX, mouseY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (handleDragEnd()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
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
                Text.translatable(selectionTitleKey()), width / 2, list.panelTop + 9, 0xFFFFFFFF);
        for (int index = 0; index < values.length; index++) {
            int x = list.x(index);
            int y = list.y(index);
            boolean hovered = mouseX >= x && mouseX < x + list.buttonWidth
                    && mouseY >= y && mouseY < y + list.buttonHeight;
            boolean selected = values[index].equalsIgnoreCase(selectionValue());
            fill(matrices, x, y, x + list.buttonWidth, y + list.buttonHeight,
                    hovered ? 0xFF3B6178 : selected ? 0xFF28533D : 0xFF303844);
            drawCenteredText(matrices, textRenderer,
                    Text.literal(SettingsSelectionList.displayName(openSelection, values[index])),
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
                fetchedModels = Collections.emptyList();
                modelListOpen = false;
                setTestStatus("", false);
            } else if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) {
                targetLanguage = values[selected];
            } else {
                outgoingTargetLanguage = values[selected];
            }
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

    /**
     * Top of the engine tab's control block: three control rows plus the read-only summary and the
     * privacy hint, centred in the band between the tab bar and Save. The shared geometry is left
     * alone because Save/Cancel and the HUD preview depend on it.
     */
    private int engineBlockTop() {
        Layout layout = layout();
        int blockHeight = layout.contentRowStep * 2 + 20 + 46;
        int available = layout.saveY - 6 - layout.contentTop;
        return layout.contentTop + Math.max(0, (available - blockHeight) / 2);
    }

    private int engineRow(int index) {
        return engineBlockTop() + layout().contentRowStep * index;
    }

    /** Read-only engine facts plus the always-visible privacy hint. */
    private void renderEngineInfo(MatrixStack matrices) {
        int y = engineRow(2) + 24;
        if (isLlm()) {
            drawCenteredText(matrices, this.textRenderer, Text.translatable(
                            "screen.universal_translator.engine.summary",
                            trimForDisplay(llmEndpoint),
                            tr(llmApiKey == null || llmApiKey.isEmpty()
                                    ? "screen.universal_translator.engine.key_unset"
                                    : "screen.universal_translator.engine.key_set")),
                    this.width / 2, y, 0xFFA0A0A0);
        } else if (!isOffline() && !LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider)) {
            drawCenteredText(matrices, this.textRenderer,
                    Text.translatable("screen.universal_translator.engine.config_file"),
                    this.width / 2, y, 0xFFA0A0A0);
        }
        drawCenteredText(matrices, this.textRenderer,
                Text.translatable(isOffline()
                        ? "screen.universal_translator.info.offline"
                        : "screen.universal_translator.info.api"),
                this.width / 2, y + 18, 0xFFFFAA55);
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
                catalog = OpenAiModelCatalog.fetchCatalog(endpointValue, keyValue);
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
        final boolean libre = LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider);
        final String endpointValue = llm ? llmEndpoint : endpoint.getText().trim();
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
                        new OpenAiChatTranslationProvider(endpointValue, keyValue, model, provider).probe();
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
    private void renderModelList(MatrixStack matrices, int mouseX, int mouseY) {
        fill(matrices, 0, 0, this.width, this.height, 0xFF101010);
        drawCenteredText(matrices, this.textRenderer,
                Text.translatable("screen.universal_translator.llm.select_model", fetchedModels.size()),
                this.width / 2, 20, 0xFFFFFFFF);
        int rows = modelRowsPerPage();
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < fetchedModels.size(); row++) {
            int rowY = MODEL_LIST_TOP + row * MODEL_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + MODEL_ROW_HEIGHT;
            drawTextWithShadow(matrices, this.textRenderer,
                    Text.literal((hovered ? "> " : "  ") + fetchedModels.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = modelPageCount(rows);
        drawCenteredText(matrices, this.textRenderer,
                Text.literal("< " + (modelPage + 1) + "/" + pages + " >"),
                this.width / 2, this.height - 46, 0xFFFFFFFF);
        drawCenteredText(matrices, this.textRenderer,
                Text.translatable("screen.universal_translator.llm.select_back"),
                this.width / 2, this.height - 30, 0xFFFFD060);
        drawCenteredText(matrices, this.textRenderer,
                Text.translatable("screen.universal_translator.llm.select_hint"),
                this.width / 2, this.height - 16, 0xFFA0A0A0);
    }

    private void handleModelListClick(double mouseX, double mouseY) {
        if (mouseY >= this.height - 54 && mouseY < this.height - 38) {
            int pages = modelPageCount(modelRowsPerPage());
            if (pages > 1) {
                modelPage = mouseX < this.width / 2.0
                        ? (modelPage + pages - 1) % pages : (modelPage + 1) % pages;
            }
            return;
        }
        if (mouseY >= this.height - 38 && mouseY < this.height - 20) {
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
        return Math.max(3, (this.height - MODEL_LIST_TOP - MODEL_LIST_FOOTER_HEIGHT) / MODEL_ROW_HEIGHT);
    }

    private int modelPageCount(int rows) {
        return Math.max(1, (fetchedModels.size() + rows - 1) / rows);
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

    private boolean handleDragStart(double mouseX, double mouseY) {
        // A selection list keeps first claim on the click: without this, the drag would swallow
        // the click that is meant to close the list.
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

    private boolean handleDragMove(double mouseX, double mouseY) {
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

    private boolean handleDragEnd() {
        if (!hudDragging) {
            return false;
        }
        hudDragging = false;
        return true;
    }

    private void drawHudDragPreview(MatrixStack matrices) {
        int px = hudPreviewX();
        int py = hudPreviewY();
        int pw = hudPreviewW();
        int ph = hudPreviewH();
        if (ph <= 0) {
            return;
        }
        fill(matrices, px, py, px + pw, py + ph, 0x40000000);
        // A corner marker on the anchor edge the indicator hugs, so the four corner options are
        // visible in the preview instead of only in the button label.
        int hx = hudHandleX();
        int hy = hudHandleY();
        fill(matrices, hx, hy, hx + HUD_DRAG_HANDLE_SIZE, hy + HUD_DRAG_HANDLE_SIZE,
                hudDragging ? 0xFFFFAA00 : 0xFF00AA00);
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

    private static String hudCornerLabel(HudIndicatorCorner corner) {
        switch (corner) {
            case TOP_RIGHT: return tr("value.universal_translator.hud_corner.top_right");
            case BOTTOM_LEFT: return tr("value.universal_translator.hud_corner.bottom_left");
            case BOTTOM_RIGHT: return tr("value.universal_translator.hud_corner.bottom_right");
            case TOP_LEFT:
            default: return tr("value.universal_translator.hud_corner.top_left");
        }
    }


    private static String tr(String key, Object... arguments) {
        return Text.translatable(key, arguments).getString();
    }

    private Layout layout() {
        SettingsScreenLayout.Geometry geometry = SettingsScreenLayout.calculate(this.width, this.height, 5);
        return new Layout(geometry.left(), geometry.right(), geometry.totalWidth(), geometry.buttonWidth(),
                geometry.top(), geometry.rowStep(), geometry.targetY(), geometry.endpointY(), geometry.saveY(),
                geometry.tabY(), geometry.tabWidth(), geometry.tabGap(), geometry.contentTop(), geometry.contentRowStep());
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
        private final int contentRowStep;

        private Layout(int left, int right, int totalWidth, int buttonWidth,
                       int top, int rowStep, int targetY, int endpointY, int saveY,
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
