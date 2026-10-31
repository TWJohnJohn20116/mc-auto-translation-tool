package org.universaltranslator.fabric;

import net.minecraft.client.MinecraftClient;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationTextColor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Fabric 1.21.9–1.21.11 translation runtime.
 *
 * <p>The body lives in {@link FabricTranslationRuntimeBase}; this file only answers the six
 * platform questions for this generation and forwards the unchanged static entry points. It
 * differs from the 1.14–1.21.8 copy in one expression: the game profile exposes {@code name()}.
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
            return MinecraftClient.getInstance().currentScreen;
        }

        @Override
        protected boolean isLocalChatInput(String text) {
            return FabricLocalTextGuard.isLocalChatInput(MinecraftClient.getInstance(), text);
        }

        @Override
        protected boolean hasConnection() {
            return MinecraftClient.getInstance().getNetworkHandler() != null;
        }

        @Override
        protected String localPlayerName() {
            return MinecraftClient.getInstance().getSession().getUsername();
        }

        @Override
        protected String serverAddress() {
            MinecraftClient client = MinecraftClient.getInstance();
            return client.getCurrentServerEntry() == null ? null : client.getCurrentServerEntry().address;
        }

        @Override
        protected Iterable<String> onlinePlayerNames() {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client.getNetworkHandler() == null) {
                return Collections.emptyList();
            }
            // A view over the tab list, not a copy: the caller stops after a thousand names anyway,
            // and collecting the whole list allocated one string per entry on the render thread.
            // A tab-list entry whose profile never arrived is the one value here that can be null;
            // without the guard one such entry threw out of the walk and left every player name
            // translatable for the rest of the session.
            return () -> client.getNetworkHandler().getPlayerList().stream()
                    .map(entry -> entry.getProfile() == null ? null : entry.getProfile().name())
                    .iterator();
        }
    }
}
