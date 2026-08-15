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
import org.universaltranslator.core.SettingsSelectionList;

/** Dependency-free settings UI shared by Forge 1.8.9 and 1.12.2. */
final class UniversalTranslatorConfigScreen extends Screen {
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
        this.llmEndpoint = config.llmEndpoint;
        this.llmApiKey = config.llmApiKey;
        this.llmModel = config.llmModel;
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
        Layout layout = layout();
        int left = layout.left;
        int styleWidth = Math.min(86, layout.buttonWidth);
        buttons.add(new ButtonWidget(UI_STYLE, Math.max(4, width - styleWidth - 6), 6,
                styleWidth, 20, ""));
        buttons.add(new ButtonWidget(ENABLED, left, layout.row(0), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(CACHE, layout.right, layout.row(0), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(CHAT, left, layout.row(1), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(OTHER, layout.right, layout.row(1), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(PROVIDER, left, layout.row(2), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(DISPLAY, layout.right, layout.row(2), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(MIXED_TEXT, left, layout.row(3), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(COLOR, layout.right, layout.row(3), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(DOWNLOAD, left, layout.row(4), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(FALLBACK, layout.right, layout.row(4), layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(MODEL, left, layout.row(5), layout.buttonWidth, 20, ""));
        blockedKeywords = new TextFieldWidget(22, renderer, layout.right, layout.row(5),
                layout.buttonWidth, 20);
        blockedKeywords.setMaxLength(4096);
        blockedKeywords.setText(blockedKeywordsValue);
        int compactGap = 4;
        int compactWidth = (layout.totalWidth - compactGap * 2) / 3;
        int compactMiddle = left + compactWidth + compactGap;
        int compactRight = compactMiddle + compactWidth + compactGap;
        buttons.add(new ButtonWidget(VANILLA, left, layout.row(6), compactWidth, 20, ""));
        buttons.add(new ButtonWidget(PLAYER_NAMES, compactMiddle, layout.row(6),
                compactWidth, 20, ""));
        buttons.add(new ButtonWidget(DIAGNOSTICS, compactRight, layout.row(6),
                compactWidth, 20, tr("screen.universal_translator.diagnostics.title")));
        buttons.add(new ButtonWidget(TARGET_LANGUAGE, left, layout.targetY,
                layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(OUTGOING, layout.right, layout.targetY,
                layout.buttonWidth, 20, ""));
        endpoint = new TextFieldWidget(21, renderer, left, layout.endpointY, layout.buttonWidth, 20);
        endpoint.setMaxLength(512);
        endpoint.setText(endpointValue);
        buttons.add(new ButtonWidget(OUTGOING_TARGET_LANGUAGE, layout.right, layout.endpointY,
                layout.buttonWidth, 20, ""));
        buttons.add(new ButtonWidget(SAVE, left, layout.saveY, layout.buttonWidth, 20,
                tr("screen.universal_translator.save")));
        buttons.add(new ButtonWidget(CANCEL, layout.right, layout.saveY, layout.buttonWidth, 20,
                tr("gui.cancel")));
        refreshLabels();
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (button.id == ENABLED) {
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
            if (isLlm()) {
                OrnitheClientAccess.openScreen(new UniversalTranslatorLlmConfigScreen(
                        this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
            } else {
                offlineAutoDownload = !offlineAutoDownload;
            }
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
        } else if (button.id == CANCEL) {
            OrnitheClientAccess.openScreen(parent);
        }
        refreshLabels();
    }

    private void refreshLabels() {
        button(UI_STYLE).message = tr("screen.universal_translator.option.ui_style",
                tr(animatedUi ? "value.universal_translator.ui_animated"
                        : "value.universal_translator.ui_classic"));
        button(ENABLED).message = tr("screen.universal_translator.option.automatic", onOff(enabled));
        button(CHAT).message = tr("screen.universal_translator.option.chat", onOff(translateChat));
        button(OTHER).message = tr("screen.universal_translator.option.other", onOff(translateOther));
        button(VANILLA).message = tr("screen.universal_translator.option.vanilla", onOff(translateVanilla));
        button(CACHE).message = tr("screen.universal_translator.option.cache", onOff(diskCache));
        button(PROVIDER).message = tr("screen.universal_translator.option.provider", providerLabel());
        button(DISPLAY).message = tr("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated"));
        button(MIXED_TEXT).message = tr("screen.universal_translator.option.mixed", onOff(translateEnglishOnly));
        button(COLOR).message = tr("screen.universal_translator.option.color", colorLabel(translatedTextColor));
        button(DOWNLOAD).message = isLlm()
                ? tr("screen.universal_translator.option.llm_settings")
                : tr("screen.universal_translator.option.download", onOff(offlineAutoDownload));
        button(MODEL).message = tr("screen.universal_translator.option.model", offlineModel.displayName());
        button(FALLBACK).message = tr("screen.universal_translator.option.fallback", onOff(apiFallback));
        button(OUTGOING).message = tr("screen.universal_translator.option.outgoing", onOff(translateOutgoing));
        button(PLAYER_NAMES).message = tr(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames));
        button(TARGET_LANGUAGE).message = tr("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage));
        button(OUTGOING_TARGET_LANGUAGE).message = tr(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage));
        button(DOWNLOAD).active = isOffline() || isLlm();
        button(MODEL).active = isOffline();
        button(FALLBACK).active = isOffline();
    }

    private ButtonWidget button(int id) {
        for (Object raw : buttons) {
            if (raw instanceof ButtonWidget) {
                ButtonWidget button = (ButtonWidget) raw;
                if (button.id == id) {
                    return button;
                }
            }
        }
        throw new IllegalStateException("Missing button " + id);
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
        endpoint.tick();
        blockedKeywords.tick();
    }

    @Override
    protected void keyPressed(char typedChar, int keyCode) {
        if (endpoint.keyPressed(typedChar, keyCode)) {
            return;
        }
        if (blockedKeywords.keyPressed(typedChar, keyCode)) {
            return;
        }
        super.keyPressed(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            selectFromList(mouseX, mouseY);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        endpoint.mouseClicked(mouseX, mouseY, mouseButton);
        blockedKeywords.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        renderBackground();
        Layout layout = layout();
        long now = System.nanoTime();
        float opening = 1.0F;
        if (animatedUi) {
            opening = SettingsUiAnimation.openProgress(animationStartedNanos, now);
            int center = width / 2;
            int half = SettingsUiAnimation.expandingHalfWidth(
                    layout.totalWidth / 2 + 12, opening);
            int panelLeft = center - half;
            int panelRight = center + half;
            int panelBottom = Math.min(height - 4, layout.saveY + 42);
            fill(0, 0, width, height, 0x76070B10);
            fill(panelLeft - 2, 2, panelRight + 2, panelBottom + 2, 0x70101820);
            fill(panelLeft, 4, panelRight, panelBottom, 0xD41A232E);
            fill(panelLeft, 4, panelRight, 5, 0xCC55D6FF);
            fill(panelLeft, 32, panelRight, 33, 0x6655D6FF);
            int sweep = SettingsUiAnimation.sweepX(
                    panelLeft, Math.max(panelLeft, panelRight - 26), now);
            fill(sweep, 32, Math.min(panelRight, sweep + 26), 34,
                    SettingsUiAnimation.pulseColor(now));
        }
        drawCenteredString(renderer, tr("screen.universal_translator.settings.title"),
                width / 2, 18,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFF);
        int left = layout.left;
        drawString(renderer, tr("screen.universal_translator.target_language_hint"),
                left, layout.targetY - 11, 0xA0A0A0);
        drawString(renderer, tr("screen.universal_translator.endpoint_hint"),
                left, layout.endpointY - 11, 0xA0A0A0);
        drawString(renderer, tr("screen.universal_translator.outgoing_target_hint"),
                layout.right, layout.endpointY - 11, 0xA0A0A0);
        endpoint.render();
        blockedKeywords.render();
        if (blockedKeywords.getText().isEmpty() && !blockedKeywords.isFocused()) {
            drawString(renderer, tr("screen.universal_translator.blocked_keywords_hint"),
                    layout.right + 4, layout.row(5) + 6, 0x808080);
        }
        String rawRuntimeStatus = FabricTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                UniversalTranslatorConfigScreen::tr);
        int belowSave = layout.saveY + 28;
        int messageY = belowSave <= height - 10 ? belowSave : layout.saveY - 14;
        if (!status.isEmpty()) {
            drawCenteredString(renderer, status, width / 2, messageY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredString(renderer, runtimeStatus, width / 2, messageY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFF5555 : 0x55FF55);
        } else if (layout.saveY - layout.endpointY >= 52) {
            int infoY = layout.endpointY + 28;
            drawCenteredString(
                    renderer,
                    tr(isOffline()
                            ? "screen.universal_translator.info.offline"
                            : "screen.universal_translator.info.api"),
                    width / 2, infoY, 0xFFAA55);
            drawCenteredString(renderer, tr("screen.universal_translator.info.keybind"),
                    width / 2, infoY + 15, 0xA0A0A0);
        }
        super.render(mouseX, mouseY, partialTicks);
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
                width / 2, list.panelTop + 9, 0xFFFFFF);
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

