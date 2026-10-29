package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationOutputValidator;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.CryptoSupport;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Baidu VIP text translation API using its documented appid/salt MD5 signature. */
public final class BaiduTranslationProvider implements TranslationProvider {
    private final URI endpoint;
    private final String appId;
    private final String secret;
    private final HttpJsonClient http;

    public BaiduTranslationProvider(String endpoint, String appId, String secret, HttpJsonClient http) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(endpoint);
        this.appId = ProviderSupport.requireCredential("Baidu app ID", appId);
        this.secret = ProviderSupport.requireCredential("Baidu secret", secret);
        this.http = http;
    }

    @Override
    public String id() {
        return "baidu";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        ProviderSupport.requireUtf8Bytes("Baidu source text", request.getText(), 6000);
        String salt = UUID.randomUUID().toString();
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("q", request.getText());
        fields.put("from", ProviderLanguageCodes.baidu(request.getSourceLanguage(), true));
        fields.put("to", ProviderLanguageCodes.baidu(request.getTargetLanguage(), false));
        fields.put("appid", appId);
        fields.put("salt", salt);
        fields.put("sign", CryptoSupport.md5Hex(appId + request.getText() + salt + secret));
        String response = http.postForm(endpoint, CryptoSupport.formEncode(fields),
                Collections.<String, String>emptyMap());
        String translated = parseTranslatedText(response);
        if (translated == null || translated.trim().isEmpty()) {
            throw providerError("Baidu", response, "error_code", "error_msg");
        }
        // trans_result[] answers one entry per requested line, so a different count means a line was
        // dropped or invented; joining with '\n' alone hid both behind a plausible-looking answer.
        requireSameLineCount(request.getText(), translated);
        return TranslationOutputValidator.requireValid(request.getText(), translated);
    }

    /** Rejects a reply whose line count differs from the request's. */
    private static void requireSameLineCount(String source, String translated) {
        int expected = countLines(source);
        int actual = countLines(translated);
        if (expected != actual) {
            throw new IllegalStateException("Baidu returned " + actual
                    + " line(s) for " + expected + " line(s) of source text");
        }
    }

    private static int countLines(String value) {
        int lines = 1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    static String parseTranslatedText(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        Object parsed;
        try {
            parsed = JsonStrings.parse(response);
        } catch (RuntimeException malformed) {
            // Fall back to reading single field if JSON structure is unexpected
            return JsonStrings.readStringField(response, "dst");
        }
        if (parsed instanceof Map) {
            Object transResult = ((Map<?, ?>) parsed).get("trans_result");
            if (transResult instanceof List) {
                List<?> items = (List<?>) transResult;
                StringBuilder joined = new StringBuilder();
                int appended = 0;
                for (Object item : items) {
                    if (item instanceof Map) {
                        Object dst = ((Map<?, ?>) item).get("dst");
                        if (dst instanceof String) {
                            if (joined.length() > 0) {
                                joined.append('\n');
                            }
                            joined.append((String) dst);
                            appended++;
                        }
                    }
                }
                if (appended != items.size()) {
                    // An entry without a usable dst used to be skipped silently, which dropped that
                    // line from the answer with nothing reported anywhere.
                    throw new IllegalStateException("Baidu returned " + items.size()
                            + " result entries but only " + appended + " carried text");
                }
                if (joined.length() > 0) {
                    return joined.toString();
                }
            }
        }
        return JsonStrings.readStringField(response, "dst");
    }

    /**
     * The exception a vendor-reported error becomes.
     *
     * <p>Kept as the call site the other six cloud providers use; the classification itself lives in
     * {@link ProviderErrors}, because it used to live here and every change to Baidu reached six
     * unrelated providers.
     */
    static Exception providerError(
            String provider,
            String response,
            String codeField,
            String messageField
    ) {
        return ProviderErrors.unreadableOr(provider, response, codeField, messageField);
    }
}
