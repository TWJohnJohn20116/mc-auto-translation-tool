package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationProviderStatus;

import java.io.InterruptedIOException;

/** Tries a privacy-preserving primary provider before an optional online fallback. */
public final class FallbackTranslationProvider
        implements TranslationProvider, TranslationProviderStatus, AutoCloseable {
    private final TranslationProvider primary;
    private final TranslationProvider fallback;
    private volatile String lastStatus = "";

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
        if (!lastStatus.isEmpty()) {
            return lastStatus;
        }
        return primary instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) primary).status()
                : "主翻译服务运行中";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        try {
            String translated = primary.translate(request);
            lastStatus = primary instanceof TranslationProviderStatus
                    ? ((TranslationProviderStatus) primary).status()
                    : "主翻译服务运行中";
            return translated;
        } catch (Exception primaryFailure) {
            if (isInterruption(primaryFailure)) {
                // The session is being cancelled (TranslationCoordinator.close() interrupts its
                // workers). Never hand the pending text to the online fallback after that: restore
                // the interrupt flag and let the caller unwind instead of transmitting off-device.
                Thread.currentThread().interrupt();
                throw primaryFailure;
            }
            try {
                String translated = fallback.translate(request);
                lastStatus = "主翻译服务失败，已使用 API 回退";
                return translated;
            } catch (Exception fallbackFailure) {
                lastStatus = "主翻译服务和 API 回退均失败";
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    private static boolean isInterruption(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof InterruptedIOException) {
                return true;
            }
        }
        return Thread.currentThread().isInterrupted();
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
