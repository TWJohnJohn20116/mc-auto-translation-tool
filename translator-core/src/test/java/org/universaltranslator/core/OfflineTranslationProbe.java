package org.universaltranslator.core;

import org.universaltranslator.core.provider.OpenAiChatTranslationProvider;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Manual loopback probe that exercises the real offline model through all protection layers. */
public final class OfflineTranslationProbe {
    private OfflineTranslationProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected a loopback chat-completions endpoint");
        }
        TranslationProvider provider = new OpenAiChatTranslationProvider(
                args[0], "", "universal-translator-local", "offline-loopback-probe");
        String original = "Welcome Steve_42 to play.example.cn:25565 | Coins: 42 | 欢迎";
        try (TranslationCoordinator coordinator = new TranslationCoordinator(
                provider, new TranslationCache(50), 1)) {
            TranslationResult result = coordinator.translate(
                    original, "auto", "zh-CN", TextKind.CHAT,
                    Arrays.asList("Steve_42"), true).get(30, TimeUnit.SECONDS);
            if (result.isFailure()) {
                throw new AssertionError("Offline translation failed: " + result.getErrorMessage());
            }
            String translated = result.getTranslatedText();
            requireContains(translated, "Steve_42");
            requireContains(translated, "play.example.cn:25565");
            requireContains(translated, "42");
            requireContains(translated, "欢迎");
            if (translated.contains("__UT_") || translated.equals(original)) {
                throw new AssertionError("Unsafe or unchanged offline result: " + translated);
            }
            System.out.println("OfflineTranslationProbe: " + translated);
        }
    }

    private static void requireContains(String text, String expected) {
        if (!text.contains(expected)) {
            throw new AssertionError("Expected protected text <" + expected + "> in <" + text + ">");
        }
    }
}
