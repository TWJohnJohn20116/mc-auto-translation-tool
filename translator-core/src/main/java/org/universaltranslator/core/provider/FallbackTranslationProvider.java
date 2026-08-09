package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationProviderStatus;

/** Tries a privacy-preserving primary provider before an optional online fallback. */
public final class FallbackTranslationProvider
        implements TranslationProvider, TranslationProviderStatus, AutoCloseable {
    private final TranslationProvider primary;
    private final TranslationProvider fallback;

    public FallbackTranslationProvider(TranslationProvider primary, TranslationProvider fallback) {
        if (primary == null || fallback == null) {
            throw new IllegalArgumentException("Both primary and fallback providers are required");
        }
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String id() {
        return "fallback:" + primary.id() + ":" + fallback.id();
    }

    @Override
    public String status() {
        return primary instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) primary).status()
                : "主翻译服务运行中";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        try {
            return primary.translate(request);
        } catch (Exception primaryFailure) {
            try {
                return fallback.translate(request);
            } catch (Exception fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        failure = closeOne(primary, failure);
        failure = closeOne(fallback, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private static Exception closeOne(TranslationProvider provider, Exception previous) {
        if (!(provider instanceof AutoCloseable)) {
            return previous;
        }
        try {
            ((AutoCloseable) provider).close();
        } catch (Exception exception) {
            if (previous == null) {
                return exception;
            }
            previous.addSuppressed(exception);
        }
        return previous;
    }
}
