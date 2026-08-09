package org.universaltranslator.core;

import org.universaltranslator.core.provider.LibreTranslateProvider;

/** One-shot loopback integration probe used by the development test script. */
public final class LibreTranslateProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one endpoint argument");
        }
        LibreTranslateProvider provider = new LibreTranslateProvider(args[0], "");
        String translated = provider.translate(new TranslationRequest(
                "Coins: __UT_0__", "auto", "zh-CN", TextKind.SCOREBOARD_LINE));
        if (!"\u91d1\u5e01: __UT_0__".equals(translated)) {
            throw new AssertionError("Unexpected provider result: " + translated);
        }
        System.out.println("LibreTranslateProbe: integration check passed");
    }
}
