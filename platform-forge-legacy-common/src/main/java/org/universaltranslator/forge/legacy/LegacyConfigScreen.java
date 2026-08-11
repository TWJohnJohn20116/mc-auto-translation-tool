package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import java.io.IOException;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.TranslationTextColor;

/** Dependency-free settings UI shared by Forge 1.8.9 and 1.12.2. */
final class LegacyConfigScreen extends GuiScreen {
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

    private final GuiScreen parent;
    private final LegacyConfig original;
    private boolean enabled;
    private boolean translateChat;
    private boolean translateOther;
    private boolean diskCache;
    private boolean offlineAutoDownload;
    private boolean apiFallback;
    private TranslationDisplayMode displayMode;
    private boolean translateEnglishOnly;
    private TranslationTextColor translatedTextColor;
    private String provider;
    private GuiTextField targetLanguage;
    private GuiTextField endpoint;
    private FontRenderer renderer;
    private String status = "";

    LegacyConfigScreen(GuiScreen parent, LegacyConfig config) {
        this.parent = parent;
        this.original = config;
        this.enabled = config.enabled;
        this.translateChat = config.translateChat;
        this.translateOther = config.translateOther;
        this.diskCache = config.diskCache;
        this.offlineAutoDownload = config.offlineAutoDownload;
        this.apiFallback = config.apiFallback;
        this.displayMode = config.displayMode;
        this.translateEnglishOnly = config.translateEnglishOnly;
        this.translatedTextColor = config.translatedTextColor;
        this.provider = config.provider;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        renderer = LegacyVersionAccess.fontRenderer();
        Layout layout = layout();
        int left = layout.left;
        buttonList.add(new GuiButton(ENABLED, left, layout.row(0), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(CACHE, layout.right, layout.row(0), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(CHAT, left, layout.row(1), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(OTHER, layout.right, layout.row(1), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(PROVIDER, left, layout.row(2), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(DISPLAY, layout.right, layout.row(2), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(MIXED_TEXT, left, layout.row(3), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(COLOR, layout.right, layout.row(3), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(DOWNLOAD, left, layout.row(4), layout.buttonWidth, 20, ""));
        buttonList.add(new GuiButton(FALLBACK, layout.right, layout.row(4), layout.buttonWidth, 20, ""));
        targetLanguage = new GuiTextField(20, renderer, left, layout.targetY, layout.buttonWidth, 20);
        targetLanguage.setMaxStringLength(32);
        targetLanguage.setText(original.targetLanguage);
        endpoint = new GuiTextField(21, renderer, left, layout.endpointY, layout.totalWidth, 20);
        endpoint.setMaxStringLength(512);
        endpoint.setText(original.endpoint);
        buttonList.add(new GuiButton(SAVE, left, layout.saveY, layout.buttonWidth, 20, "保存并应用"));
        buttonList.add(new GuiButton(CANCEL, layout.right, layout.saveY, layout.buttonWidth, 20, "取消"));
        refreshLabels();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ENABLED) {
            enabled = !enabled;
        } else if (button.id == CACHE) {
            diskCache = !diskCache;
        } else if (button.id == CHAT) {
            translateChat = !translateChat;
        } else if (button.id == OTHER) {
            translateOther = !translateOther;
        } else if (button.id == PROVIDER) {
            provider = nextProvider(provider);
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
        } else if (button.id == FALLBACK) {
            apiFallback = !apiFallback;
        } else if (button.id == SAVE) {
            saveAndApply();
        } else if (button.id == CANCEL) {
            mc.displayGuiScreen(parent);
        }
        refreshLabels();
    }

    private void refreshLabels() {
        button(ENABLED).displayString = "自动翻译: " + onOff(enabled);
        button(CHAT).displayString = "聊天内容: " + onOff(translateChat);
        button(OTHER).displayString = "其他界面: " + onOff(translateOther);
        button(CACHE).displayString = "本地缓存: " + onOff(diskCache);
        button(PROVIDER).displayString = "服务: " + providerLabel();
        button(DISPLAY).displayString = "显示: "
                + (displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED ? "原文+译文" : "仅译文");
        button(MIXED_TEXT).displayString = "混合文本仅译英文: " + onOff(translateEnglishOnly);
        button(COLOR).displayString = "译文颜色: " + colorLabel(translatedTextColor);
        button(DOWNLOAD).displayString = "模型下载: " + onOff(offlineAutoDownload);
        button(FALLBACK).displayString = "API 回退: " + onOff(apiFallback);
        button(DOWNLOAD).enabled = isOffline();
        button(FALLBACK).enabled = isOffline();
    }

    private GuiButton button(int id) {
        for (GuiButton button : buttonList) {
            if (button.id == id) {
                return button;
            }
        }
        throw new IllegalStateException("Missing button " + id);
    }

    private static String onOff(boolean value) {
        return value ? "开启" : "关闭";
    }

    private static boolean isFailureStatus(String value) {
        return value.startsWith("翻译失败") || value.startsWith("离线翻译失败")
                || value.contains("均失败");
    }

    private void saveAndApply() {
        boolean runtimeChanged = false;
        try {
            if (targetLanguage.getText().trim().isEmpty()) {
                throw new IllegalArgumentException("目标语言不能为空");
            }
            LegacyConfig updated = original.withSettings(
                    enabled,
                    translateChat,
                    translateOther,
                    targetLanguage.getText(),
                    displayMode,
                    translateEnglishOnly,
                    translatedTextColor,
                    provider,
                    endpoint.getText(),
                    offlineAutoDownload,
                    apiFallback,
                    diskCache);
            if (updated.enabled && "tencent-hunyuan".equalsIgnoreCase(updated.provider)
                    && (updated.tencentSecretId.isEmpty() || updated.tencentSecretKey.isEmpty())) {
                throw new IllegalArgumentException("请先在本地配置文件填写腾讯 SecretId 和 SecretKey");
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
            status = "无法保存: " + exception.getMessage();
        }
    }

    @Override
    public void updateScreen() {
        targetLanguage.updateCursorCounter();
        endpoint.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (targetLanguage.textboxKeyTyped(typedChar, keyCode)
                || endpoint.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        targetLanguage.mouseClicked(mouseX, mouseY, mouseButton);
        endpoint.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(renderer, "MC 自动翻译工具 设置", width / 2, 18, 0xFFFFFF);
        Layout layout = layout();
        int left = layout.left;
        drawString(renderer, "目标语言 (例如 zh-CN)", left, layout.targetY - 11, 0xA0A0A0);
        drawString(renderer, "LibreTranslate /translate 地址（仅 API 模式/回退使用）",
                left, layout.endpointY - 11, 0xA0A0A0);
        targetLanguage.drawTextBox();
        endpoint.drawTextBox();
        String runtimeStatus = LegacyTranslationRuntime.status();
        int belowSave = layout.saveY + 28;
        int messageY = belowSave <= height - 10 ? belowSave : layout.saveY - 14;
        if (!status.isEmpty()) {
            drawCenteredString(renderer, status, width / 2, messageY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredString(renderer, runtimeStatus, width / 2, messageY,
                    isFailureStatus(runtimeStatus) ? 0xFF5555 : 0x55FF55);
        } else if (layout.saveY - layout.endpointY >= 52) {
            int infoY = layout.endpointY + 28;
            drawCenteredString(
                    renderer,
                    isOffline()
                            ? "离线模式只访问本机；首次使用会在后台下载约 502 MB。"
                            : "API 模式会把选中的服务器文字发送到翻译服务。",
                    width / 2, infoY, 0xFFAA55);
            drawCenteredString(renderer, "F8 一键开关；可在 设置 → 控制 → 按键绑定 中修改。",
                    width / 2, infoY + 15, 0xA0A0A0);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private boolean isTencent() {
        return "tencent-hunyuan".equalsIgnoreCase(provider);
    }

    private boolean isOffline() {
        return "offline".equalsIgnoreCase(provider);
    }

    private String providerLabel() {
        return isOffline() ? "离线" : (isTencent() ? "腾讯" : "Libre");
    }

    private static String nextProvider(String current) {
        if ("offline".equalsIgnoreCase(current)) {
            return "libretranslate";
        }
        if ("libretranslate".equalsIgnoreCase(current)) {
            return "tencent-hunyuan";
        }
        return "offline";
    }

    private static String colorLabel(TranslationTextColor color) {
        switch (color) {
            case ORIGINAL: return "保留原色";
            case GREEN: return "绿色";
            case GOLD: return "金色";
            case LIGHT_PURPLE: return "浅紫";
            case YELLOW: return "黄色";
            case WHITE: return "白色";
            case AQUA:
            default: return "青色";
        }
    }

    private Layout layout() {
        int totalWidth = Math.max(180, Math.min(310, width - 20));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;
        int top = Math.max(20, Math.min(44, 20 + Math.max(0, height - 220) / 4));
        int rowStep = height >= 300 ? 26 : 22;
        int targetY = top + rowStep * 5 + 2;
        int endpointY = targetY + (height >= 300 ? 32 : 28);
        int saveY = height >= 330 ? 270 : Math.max(endpointY + 22, height - 24);
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
