package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.CryptoSupport;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** User-configurable HTTPS/loopback JSON translation endpoint without arbitrary code execution. */
public final class CustomHttpJsonTranslationProvider implements TranslationProvider {
    /**
     * Characters a header name may use.
     *
     * <p>This is deliberately the transport's set and not RFC 7230's full {@code tchar}: the check
     * here runs while the setting is edited, while the transport checks every request, so a wider set
     * let a legal-looking name ({@code X_API_Key}, {@code X.Trace}) pass construction and throw
     * {@code IllegalArgumentException} on every translation afterwards — which is neither retried nor
     * handed to the fallback, so that provider was permanently unusable.
     */
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,128}");

    private final URI endpoint;
    private final String method;
    private final String contentType;
    private final String requestTemplate;
    private final String responsePath;
    private final Map<String, String> headerTemplates;
    private final String apiKey;
    private final HttpJsonClient http;
    private final String id;

    public CustomHttpJsonTranslationProvider(
            String endpoint,
            String method,
            String contentType,
            String requestTemplate,
            String responsePath,
            Map<String, String> headerTemplates,
            String apiKey,
            HttpJsonClient http
    ) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(endpoint);
        this.method = normalizeMethod(method);
        this.contentType = safeLine("Custom API content type", contentType);
        this.requestTemplate = requireTemplate(requestTemplate);
        this.responsePath = ProviderSupport.requireCredential("Custom API response JSON path", responsePath);
        this.apiKey = ProviderSupport.optional(apiKey);
        this.http = http;
        this.headerTemplates = validateHeaders(headerTemplates);
        // The coordinator keys both its in-flight map and the disk cache on this id, so two
        // configurations that share a host but differ in request/response semantics must not
        // collide. Hash the path, the query, the port, the content type and the templates, never
        // the credentials or a resolved header value.
        // The old fingerprint stopped at the path, so moving the endpoint from :8080 to :9090, or
        // changing only a header (the usual way to switch models on a local server), kept the old
        // id and served the previous translation from the disk cache.
        // Every input is fixed once construction succeeds, so hash the template once here instead of
        // hashing up to 64 KiB on every id() call from the render thread.
        String fingerprint = this.endpoint.getScheme() + "://" + this.endpoint.getAuthority()
                + this.endpoint.getRawPath()
                + "\n" + (this.endpoint.getRawQuery() == null ? "" : this.endpoint.getRawQuery())
                + "\n" + this.method
                + "\n" + this.contentType
                + "\n" + this.responsePath + "\n" + this.requestTemplate
                + "\n" + headerFingerprint();
        this.id = "custom-http-json:" + this.endpoint.getAuthority()
                + ":" + CryptoSupport.sha256Hex(fingerprint);
    }

    /**
     * Names and templates of the configured headers, with a pasted credential masked out.
     *
     * <p>The template decides the request semantics; the resolved value does not, and a user who put
     * the key straight into a header must not get a different cache key every time that key is
     * rotated. Insertion order is stable, so the same configuration always hashes the same way.
     */
    private String headerFingerprint() {
        if (headerTemplates.isEmpty()) {
            return "";
        }
        StringBuilder fingerprint = new StringBuilder(headerTemplates.size() * 32);
        for (Map.Entry<String, String> header : headerTemplates.entrySet()) {
            String template = header.getValue() == null ? "" : header.getValue();
            if (!apiKey.isEmpty()) {
                template = template.replace(apiKey, "{apiKey}");
            }
            fingerprint.append(header.getKey()).append('=').append(template).append('\n');
        }
        return fingerprint.toString();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        String source = ProviderLanguageCodes.common(request.getSourceLanguage(), true);
        String target = ProviderLanguageCodes.common(request.getTargetLanguage(), false);
        String body = expand(requestTemplate, request.getText(), source, target, apiKey);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> header : headerTemplates.entrySet()) {
            String value = safeLine("Custom API header value",
                    expand(header.getValue(), request.getText(), source, target, apiKey));
            if (!value.isEmpty()) {
                headers.put(header.getKey(), value);
            }
        }
        String response = http.request(method, endpoint, body, contentType, headers);
        String translated = ProviderErrors.pathOrNull("Custom API", response, responsePath);
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Custom API response path did not resolve to a non-empty string: " + responsePath);
        }
        return translated;
    }

    /** Placeholders a template may use, longest first so {@code ${textJson}} cannot match {@code ${text}}. */
    private static final String[] PLACEHOLDERS = {
            "${textJson}", "${sourceJson}", "${targetJson}", "${apiKeyJson}",
            "${text}", "${source}", "${target}", "${apiKey}", "${apiKeyRaw}"
    };

    static String expand(String template, String text, String source, String target, String apiKey) {
        StringBuilder expanded = new StringBuilder(template.length() + text.length() + 32);
        int cursor = 0;
        // Scans for the next "${" instead of trying all nine placeholders at every character, and
        // quotes a value only when its placeholder actually occurs: a template with one placeholder
        // paid nine quotes and nine character-by-character comparisons per call before.
        while (cursor < template.length()) {
            int dollar = template.indexOf('$', cursor);
            if (dollar < 0) {
                expanded.append(template, cursor, template.length());
                break;
            }
            expanded.append(template, cursor, dollar);
            int placeholder = placeholderAt(template, dollar);
            if (placeholder < 0) {
                expanded.append(template.charAt(dollar));
                cursor = dollar + 1;
                continue;
            }
            expanded.append(replacement(placeholder, text, source, target, apiKey));
            cursor = dollar + PLACEHOLDERS[placeholder].length();
        }
        return expanded.toString();
    }

    /** Index into {@link #PLACEHOLDERS} of the placeholder starting at {@code offset}, or {@code -1}. */
    private static int placeholderAt(String template, int offset) {
        for (int index = 0; index < PLACEHOLDERS.length; index++) {
            if (template.startsWith(PLACEHOLDERS[index], offset)) {
                return index;
            }
        }
        return -1;
    }

    private static String replacement(int placeholder, String text, String source, String target,
            String apiKey) {
        if (placeholder == 0) return JsonStrings.quote(text);
        if (placeholder == 1) return JsonStrings.quote(source);
        if (placeholder == 2) return JsonStrings.quote(target);
        if (placeholder == 3) return JsonStrings.quote(apiKey);
        if (placeholder == 4) return jsonStringContent(text);
        if (placeholder == 5) return jsonStringContent(source);
        if (placeholder == 6) return jsonStringContent(target);
        if (placeholder == 7) return jsonStringContent(apiKey);
        return apiKey;
    }

    private static String jsonStringContent(String value) {
        String quoted = JsonStrings.quote(value);
        return quoted.substring(1, quoted.length() - 1);
    }

    private static String requireTemplate(String value) {
        String template = ProviderSupport.requireCredential("Custom API request template", value);
        if (template.length() > 65536) {
            throw new IllegalArgumentException("Custom API request template exceeds 64 KiB");
        }
        if (!template.contains("${textJson}") && !template.contains("${text}")) {
            throw new IllegalArgumentException("Custom API request template must contain ${textJson} or ${text}");
        }
        return template;
    }

    private static Map<String, String> validateHeaders(Map<String, String> source) {
        LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
        if (source == null) {
            return result;
        }
        if (source.size() > 32) {
            throw new IllegalArgumentException("Custom API supports at most 32 request headers");
        }
        for (Map.Entry<String, String> header : source.entrySet()) {
            String name = header.getKey() == null ? "" : header.getKey().trim();
            if (!HEADER_NAME.matcher(name).matches() || isForbiddenHeader(name)) {
                throw new IllegalArgumentException("Unsafe custom API header: " + name
                        + " (a header name may use letters, digits and '-')");
            }
            // A header value is one line. Expanding ${text} into it here would pass the check below
            // and then throw on the first multi-line source, which is neither retried nor handed to
            // the fallback, so that line failed for good with a message naming no header.
            if (containsTextPlaceholder(header.getValue())) {
                throw new IllegalArgumentException("Custom API header " + name
                        + " must not contain ${text} or ${textJson}: a header value cannot hold"
                        + " multi-line text");
            }
            result.put(name, safeLine("Custom API header value", header.getValue()));
        }
        return result;
    }

    private static boolean containsTextPlaceholder(String template) {
        return template != null
                && (template.contains("${text}") || template.contains("${textJson}"));
    }

    private static boolean isForbiddenHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return "host".equals(lower) || "content-length".equals(lower)
                || "transfer-encoding".equals(lower) || "connection".equals(lower)
                || "proxy-authorization".equals(lower) || "upgrade".equals(lower);
    }

    private static String normalizeMethod(String value) {
        String method = ProviderSupport.requireCredential("Custom API method", value).toUpperCase(Locale.ROOT);
        if (!"POST".equals(method) && !"PUT".equals(method)) {
            throw new IllegalArgumentException("Custom API method must be POST or PUT");
        }
        return method;
    }

    private static String safeLine(String name, String value) {
        String result = value == null ? "" : value.trim();
        if (result.indexOf('\r') >= 0 || result.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " must not contain newlines");
        }
        return result;
    }
}
