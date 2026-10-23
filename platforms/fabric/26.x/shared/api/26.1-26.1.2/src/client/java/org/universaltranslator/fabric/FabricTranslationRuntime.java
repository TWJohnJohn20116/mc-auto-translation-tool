package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationTextColor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Fabric 26.1–26.1.2 translation runtime.
 *
 * <p>The body lives in {@link FabricTranslationRuntimeBase}; this file only answers the six
 * platform questions for this generation and forwards the unchanged static entry points. These
 * versions keep the pre-26.2 screen accessor {@code client.screen}.
 */
public final class FabricTranslationRuntime {
    private static final Impl INSTANCE = new Impl();

    private FabricTranslationRuntime() {
    }

    static void initialize(FabricConfig config) throws IOException {
        INSTANCE.initialize(config);
    }

    static String translateForRender(String original, TextKind kind) {
        return INSTANCE.translateForRender(original, kind);
    }

    static void shutdown() {
        INSTANCE.shutdown();
    }

    static String status() {
        return INSTANCE.status();
    }

    static TranslationDiagnosticsSnapshot diagnostics() {
        return INSTANCE.diagnostics();
    }

    static Path exportDiagnostics(List<String> localizedLines) throws IOException {
        return INSTANCE.exportDiagnostics(localizedLines);
    }

    static List<String> translateLinesForRender(List<String> originals, TextKind kind) {
        return INSTANCE.translateLinesForRender(originals, kind);
    }

    static TranslationTextColor translatedTextColor() {
        return INSTANCE.translatedTextColor();
    }

    static void protectOutgoingMessage(String message) {
        INSTANCE.protectOutgoingMessage(message);
    }

    static boolean shouldTranslateOutgoing(String message) {
        return INSTANCE.shouldTranslateOutgoing(message);
    }

    static CompletableFuture<TranslationResult> translateOutgoing(String message) {
        return INSTANCE.translateOutgoing(message);
    }

    public static HomeQuickSettingsState homeSettings() {
        return INSTANCE.homeSettings();
    }

    public static HomeQuickSettingsState toggleHomeEnabled() throws Exception {
        return INSTANCE.toggleHomeEnabled();
    }

    public static HomeQuickSettingsState toggleHomeVanilla() throws Exception {
        return INSTANCE.toggleHomeVanilla();
    }

    public static HomeQuickSettingsState cycleHomeTargetLanguage() throws Exception {
        return INSTANCE.cycleHomeTargetLanguage();
    }

    static FabricConfig diagnosticsConfig() {
        return INSTANCE.diagnosticsConfig();
    }

    private static final class Impl extends FabricTranslationRuntimeBase {
        @Override
        protected Object currentScreen() {
            return Minecraft.getInstance().screen;
        }

        @Override
        protected boolean isLocalChatInput(String text) {
            return FabricLocalTextGuard.isLocalChatInput(Minecraft.getInstance(), text);
        }

        @Override
        protected boolean hasConnection() {
            return Minecraft.getInstance().getConnection() != null;
        }

        @Override
        protected String localPlayerName() {
            return Minecraft.getInstance().getUser().getName();
        }

        @Override
        protected String serverAddress() {
            Minecraft client = Minecraft.getInstance();
            return client.getCurrentServer() == null ? null : client.getCurrentServer().ip;
        }

        @Override
        protected Iterable<String> onlinePlayerNames() {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() == null) {
                return Collections.emptyList();
            }
            List<String> names = new ArrayList<String>();
            client.getConnection().getOnlinePlayers().forEach(entry ->
                    names.add(entry.getProfile().name()));
            return names;
        }
    }
}
