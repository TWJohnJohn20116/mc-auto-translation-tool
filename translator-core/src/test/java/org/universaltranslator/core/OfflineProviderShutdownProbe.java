package org.universaltranslator.core;

import org.universaltranslator.core.provider.LlamaCppOfflineProvider;

import java.nio.file.Paths;

/** Manual probe that deliberately relies on the JVM shutdown hook to stop llama.cpp. */
public final class OfflineProviderShutdownProbe {
    private OfflineProviderShutdownProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected an installed offline root");
        }
        LlamaCppOfflineProvider provider = new LlamaCppOfflineProvider(
                Paths.get(args[0]), false);
        String translated = provider.translate(new TranslationRequest(
                "Open the chest", "en", "zh-CN", TextKind.TOOLTIP));
        if (translated == null || translated.trim().isEmpty()
                || "Open the chest".equals(translated.trim())) {
            throw new AssertionError("Offline translation did not produce a translation");
        }
        System.out.println("OfflineProviderShutdownProbe: " + translated);
        // Intentionally do not call close(). Normal JVM shutdown must stop the child.
    }
}
