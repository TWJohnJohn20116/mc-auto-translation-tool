package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
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
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}");

    private final URI endpoint;
    private final String method;
    private final String contentType;
    private final String requestTemplate;
    private final String responsePath;
    private final Map<String, String> headerTemplates;
    private final String apiKey;
    private final HttpJsonClient http;

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
    }

    @Override
    public String id() {
        return "custom-http-json:" + endpoint.getHost();
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
        String translated = JsonStrings.readStringPath(response, responsePath);
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Custom API response path did not resolve to a non-empty string: " + responsePath);
        }
        return translated;
    }

    static String expand(String template, String text, String source, String target, String apiKey) {
        String[] names = {
                "${textJson}", "${sourceJson}", "${targetJson}", "${apiKeyJson}",
                "${text}", "${source}", "${target}", "${apiKey}"
        };
        String[] replacements = {
                JsonStrings.quote(text), JsonStrings.quote(source), JsonStrings.quote(target),
                JsonStrings.quote(apiKey), jsonStringContent(text), jsonStringContent(source),
                jsonStringContent(target), apiKey
        };
        StringBuilder expanded = new StringBuilder(template.length() + text.length() + 32);
        for (int cursor = 0; cursor < template.length();) {
            boolean replaced = false;
            for (int index = 0; index < names.length; index++) {
                if (template.startsWith(names[index], cursor)) {
                    expanded.append(replacements[index]);
                    cursor += names[index].length();
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                expanded.append(template.charAt(cursor++));
            }
        }
        return expanded.toString();
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
                throw new IllegalArgumentException("Unsafe custom API header: " + name);
            }
            result.put(name, safeLine("Custom API header value", header.getValue()));
        }
        return result;
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
