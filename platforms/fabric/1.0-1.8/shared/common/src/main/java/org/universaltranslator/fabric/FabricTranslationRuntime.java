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
 * Fabric 1.3.1–1.8.8 translation runtime.
 *
 * <p>The body lives in {@link FabricTranslationRuntimeBase}; this file only answers the six
 * platform questions for this generation and forwards the unchanged static entry points. The
 * session name and the server address come from {@code OrnitheClientAccess} because the 1.3–1.8
 * client exposes them through different members than later Ornithe versions.
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
            return Minecraft.getInstance().getNetworkHandler() != null;
        }

        @Override
        protected String localPlayerName() {
            return OrnitheClientAccess.sessionUsername();
        }

        @Override
        protected String serverAddress() {
            return OrnitheClientAccess.currentServerAddress();
        }

        @Override
        protected Iterable<String> onlinePlayerNames() {
            Minecraft client = Minecraft.getInstance();
            if (client.getNetworkHandler() == null) {
                return Collections.emptyList();
            }
            List<String> names = new ArrayList<String>();
            for (Object raw : client.getNetworkHandler().onlinePlayers) {
                if (raw instanceof net.minecraft.client.network.PlayerInfo) {
                    net.minecraft.client.network.PlayerInfo entry =
                            (net.minecraft.client.network.PlayerInfo) raw;
                    names.add(entry.name);
                }
            }
            return names;
        }
    }
}
