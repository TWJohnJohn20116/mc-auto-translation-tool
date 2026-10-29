package org.universaltranslator.core.net;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** A refused translation connection that will not recover through immediate retries. */
public final class TranslationEndpointUnavailableException extends IOException {
    private TranslationEndpointUnavailableException(String message, IOException cause) {
        super(message, cause);
    }

    public static TranslationEndpointUnavailableException connectionRefused(
            URI endpoint,
            IOException cause
    ) {
        String host = endpoint == null ? null : endpoint.getHost();
        int port = effectivePort(endpoint);
        String address = displayAddress(host, port);
        String message;
        if (isLoopbackHost(host)) {
            message = "本机翻译服务未启动（" + address
                    + "）；请启动对应服务，或在模组设置中改用可用提供商";
        } else {
            message = "无法连接翻译服务（" + address
                    + "）；请检查服务地址、网络和服务状态";
        }
        return new TranslationEndpointUnavailableException(message, cause);
    }

    /** A host name that never resolved: a typo in the endpoint, or DNS itself is down. */
    public static TranslationEndpointUnavailableException unknownHost(URI endpoint, IOException cause) {
        String address = displayAddress(endpoint == null ? null : endpoint.getHost(),
                effectivePort(endpoint));
        return new TranslationEndpointUnavailableException("找不到翻译服务主机（" + address
                + "）；请检查服务地址与 DNS 设置", cause);
    }

    /** The TLS handshake failed: an expired or untrusted certificate, or an intercepting proxy. */
    public static TranslationEndpointUnavailableException tlsFailure(URI endpoint, IOException cause) {
        String address = displayAddress(endpoint == null ? null : endpoint.getHost(),
                effectivePort(endpoint));
        return new TranslationEndpointUnavailableException("无法与翻译服务建立 TLS 连接（" + address
                + "）；请检查证书与系统代理设置", cause);
    }

    private static int effectivePort(URI endpoint) {
        if (endpoint == null) {
            return -1;
        }
        if (endpoint.getPort() >= 0) {
            return endpoint.getPort();
        }
        if ("https".equalsIgnoreCase(endpoint.getScheme())) {
            return 443;
        }
        if ("http".equalsIgnoreCase(endpoint.getScheme())) {
            return 80;
        }
        return -1;
    }

    private static String displayAddress(String host, int port) {
        String safeHost = host == null || host.trim().isEmpty()
                ? "已配置地址"
                : host.replace('\n', ' ').replace('\r', ' ').trim();
        if (safeHost.indexOf(':') >= 0 && !safeHost.startsWith("[")) {
            safeHost = "[" + safeHost + "]";
        }
        return port < 0 ? safeHost : safeHost + ":" + port;
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }
}
