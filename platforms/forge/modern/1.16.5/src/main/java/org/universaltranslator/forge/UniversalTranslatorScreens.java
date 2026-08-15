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
        this.llmEndpoint = config.llmEndpoint;
        this.llmApiKey = config.llmApiKey;
        this.llmModel = config.llmModel;
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
        int styleWidth = Math.min(86, layout.buttonWidth);
        uiStyleButton = addButton(button(Math.max(4, width - styleWidth - 6), 6,
                styleWidth, () -> {
                    animatedUi = !animatedUi;
                    animationStartedNanos = System.nanoTime();
                }));
        enabledButton = addButton(button(left, layout.row(0), layout.buttonWidth,
                () -> enabled = !enabled));
        cacheButton = addButton(button(layout.right, layout.row(0), layout.buttonWidth,
                () -> diskCache = !diskCache));
        chatButton = addButton(button(left, layout.row(1), layout.buttonWidth,
                () -> translateChat = !translateChat));
        otherButton = addButton(button(layout.right, layout.row(1), layout.buttonWidth,
                () -> translateOther = !translateOther));
        providerButton = addButton(button(left, layout.row(2), layout.buttonWidth,
                () -> openSelection = SettingsSelectionList.Kind.PROVIDER));
        displayButton = addButton(button(layout.right, layout.row(2), layout.buttonWidth,
                () -> displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? TranslationDisplayMode.TRANSLATED_ONLY
                        : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED));
        mixedTextButton = addButton(button(left, layout.row(3), layout.buttonWidth,
                () -> translateEnglishOnly = !translateEnglishOnly));
        colorButton = addButton(button(layout.right, layout.row(3), layout.buttonWidth,
                () -> translatedTextColor = translatedTextColor.next()));
        downloadButton = addButton(button(left, layout.row(4), layout.buttonWidth, () -> {
            if (isLlm()) {
                if (minecraft != null) {
                    minecraft.setScreen(new UniversalTranslatorLlmConfigScreen(
                            this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
                }
            } else {
                offlineAutoDownload = !offlineAutoDownload;
            }
        }));
        fallbackButton = addButton(button(layout.right, layout.row(4), layout.buttonWidth,
                () -> apiFallback = !apiFallback));
        modelButton = addButton(button(left, layout.row(5), layout.buttonWidth,
                () -> offlineModel = offlineModel.next()));
        blockedKeywords = addButton(new TextFieldWidget(font, layout.right, layout.row(5),
                layout.buttonWidth, 20, new TranslationTextComponent("screen.universal_translator.blocked_keywords")));
        blockedKeywords.setMaxLength(4096);
        blockedKeywords.setValue(blockedKeywordsValue);

        int compactGap = 4;
        int compactWidth = (layout.totalWidth - compactGap * 2) / 3;
        int compactMiddle = left + compactWidth + compactGap;
        int compactRight = compactMiddle + compactWidth + compactGap;
        vanillaButton = addButton(button(left, layout.row(6), compactWidth,
                () -> translateVanilla = !translateVanilla));
        playerNamesButton = addButton(button(compactMiddle, layout.row(6), compactWidth,
                () -> translatePlayerNames = !translatePlayerNames));
        addButton(new Button(compactRight, layout.row(6), compactWidth, 20,
                new TranslationTextComponent("screen.universal_translator.diagnostics.title"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new UniversalTranslatorDiagnosticsScreen(this, original));
                    }
                }));

        targetLanguageButton = addButton(button(left, layout.targetY, layout.buttonWidth,
                () -> openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE));
        outgoingButton = addButton(button(layout.right, layout.targetY, layout.buttonWidth,
                () -> translateOutgoing = !translateOutgoing));
        endpoint = addButton(new TextFieldWidget(font, left, layout.endpointY, layout.buttonWidth,
                20, new TranslationTextComponent("screen.universal_translator.endpoint")));
        endpoint.setMaxLength(512);
        endpoint.setValue(endpointValue);
        outgoingTargetLanguageButton = addButton(button(layout.right, layout.endpointY,
                layout.buttonWidth, () -> openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE));
        addButton(new Button(left, layout.saveY, layout.buttonWidth, 20,
                new TranslationTextComponent("screen.universal_translator.save"), button -> saveAndApply()));
        addButton(new Button(layout.right, layout.saveY, layout.buttonWidth, 20,
                new TranslationTextComponent("gui.cancel"), button -> onClose()));
        refreshLabels();
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
        cacheButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.cache", onOff(diskCache)));
        chatButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.chat", onOff(translateChat)));
        otherButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.other", onOff(translateOther)));
        providerButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.provider",
                TranslationProviderCatalog.displayName(provider)));
        displayButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        mixedTextButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.mixed",
                onOff(translateEnglishOnly)));
        colorButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.color",
                colorLabel(translatedTextColor)));
        downloadButton.setMessage(isLlm()
                ? new TranslationTextComponent("screen.universal_translator.option.llm_settings")
                : new TranslationTextComponent("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        modelButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.model", offlineModel.displayName()));
        fallbackButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.fallback", onOff(apiFallback)));
        vanillaButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        playerNamesButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.player_names",
                onOff(translatePlayerNames)));
        targetLanguageButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage)));
        outgoingButton.setMessage(new TranslationTextComponent("screen.universal_translator.option.outgoing",
                onOff(translateOutgoing)));
        outgoingTargetLanguageButton.setMessage(new TranslationTextComponent(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage)));
        downloadButton.active = isOffline() || isLlm();
        modelButton.active = isOffline();
        fallbackButton.active = isOffline();
    }

    private void saveAndApply() {
        boolean runtimeChanged = false;
        try {
            ForgeConfig updated = original.withSettings(enabled, translateChat, translateOther,
                    translateVanilla, translateOutgoing, translatePlayerNames,
                    blockedKeywords.getValue(), targetLanguage, outgoingTargetLanguage, displayMode,
                    translateEnglishOnly, translatedTextColor, provider, endpoint.getValue(),
                    llmEndpoint, llmApiKey, llmModel, offlineAutoDownload, offlineModel,
                    apiFallback, diskCache, animatedUi);
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
        }
        graphics.centered(title, width / 2, 18,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFF);
        graphics.text(new TranslationTextComponent("screen.universal_translator.target_language_hint"),
                layout.left, layout.targetY - 11, 0xA0A0A0);
        graphics.text(new TranslationTextComponent("screen.universal_translator.endpoint_hint"),
                layout.left, layout.endpointY - 11, 0xA0A0A0);
        graphics.text(new TranslationTextComponent("screen.universal_translator.outgoing_target_hint"),
                layout.right, layout.endpointY - 11, 0xA0A0A0);
        String rawStatus = ForgeTranslationRuntime.status();
        String localized = TranslationStatusLocalizer.localize(rawStatus,
                UniversalTranslatorConfigScreenBase::tr);
        int belowSave = layout.saveY + 28;
        int messageY = belowSave <= height - 10 ? belowSave : layout.saveY - 14;
        if (!status.isEmpty()) {
            graphics.centered(new StringTextComponent(status), width / 2, messageY, 0xFF5555);
        } else if (!localized.isEmpty()) {
            graphics.centered(new StringTextComponent(localized), width / 2, messageY,
                    TranslationStatusLocalizer.isFailure(rawStatus) ? 0xFF5555 : 0x55FF55);
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
                width / 2, list.panelTop + 9, 0xFFFFFF);
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
                    selected ? 0x55FF88 : 0xFFFFFF);
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
            if (openSelection == SettingsSelectionList.Kind.PROVIDER) provider = values[selected];
            else if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) targetLanguage = values[selected];
            else outgoingTargetLanguage = values[selected];
            openSelection = SettingsSelectionList.Kind.NONE;
            refreshLabels();
        } else if (!list.contains(mouseX, mouseY)) {
            openSelection = SettingsSelectionList.Kind.NONE;
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
        int totalWidth = Math.max(180, Math.min(310, width - 20));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;
        int top = Math.max(20, Math.min(44, 20 + Math.max(0, height - 220) / 4));
        int rowStep = height >= 300 ? 26 : (height >= 260 ? 22 : 20);
        int targetY = top + rowStep * 7 + 2;
        int endpointY = targetY + (height >= 300 ? 32 : 28);
        int saveY = height >= 330 ? 296 : Math.max(endpointY + 22, height - 24);
        return new Layout(left, left + buttonWidth + gap, totalWidth, buttonWidth,
                top, rowStep, targetY, endpointY, saveY);
    }

    private static final class Layout {
        private final int left, right, totalWidth, buttonWidth, top, rowStep, targetY, endpointY, saveY;

        private Layout(int left, int right, int totalWidth, int buttonWidth, int top,
                       int rowStep, int targetY, int endpointY, int saveY) {
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
        graphics.centered(title, width / 2, 18, 0xFFFFFF);
        List<String> lines = safeLines();
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            graphics.text(new StringTextComponent(line), left, y, 0xD0D0D0);
            y += 17;
        }
        graphics.centered(new TranslationTextComponent("screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 58), 0x909090);
        if (!exportStatus.isEmpty()) {
            graphics.centered(new StringTextComponent(exportStatus), width / 2, height - 43,
                    exportFailed ? 0xFF5555 : 0x55FF88);
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
        graphics.centered(title, width / 2, 18, 0xFFFFFF);
        graphics.text(new TranslationTextComponent("screen.universal_translator.llm.endpoint_hint"),
                left, top - 11, 0xA0A0A0);
        graphics.text(new TranslationTextComponent("screen.universal_translator.llm.model_hint"),
                left, top + 25, 0xA0A0A0);
        graphics.text(new TranslationTextComponent(hasStoredKey
                        ? "screen.universal_translator.llm.key_saved_hint"
                        : "screen.universal_translator.llm.key_empty_hint"),
                left, top + 61, 0xA0A0A0);
        if (!status.isEmpty()) {
            graphics.centered(new StringTextComponent(status), width / 2, top + 134, 0xFF5555);
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
