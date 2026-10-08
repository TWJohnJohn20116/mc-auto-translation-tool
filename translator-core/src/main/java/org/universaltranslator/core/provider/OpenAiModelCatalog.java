package org.universaltranslator.core.provider;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;

/**
 * Reads the OpenAI-compatible {@code /models} catalog.
 *
 * <p>The settings screen uses this so a user can pick a served model instead of typing an exact
 * identifier. Parsing is deliberately tolerant: the official {@code {"data":[{"id":...}]}} shape,
 * the Ollama {@code {"models":[{"name":...}]}} shape, and a bare array of names are all accepted.
 * Nothing here talks to the network on its own; {@link #fetch} is the only entry point that does.
 */
public final class OpenAiModelCatalog {
    /** Guards the settings list against a provider that publishes an unbounded catalog. */
    public static final int MAXIMUM_MODELS = 200;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 15000;
    private static final String CHAT_COMPLETIONS_SUFFIX = "/chat/completions";
    private static final String COMPLETIONS_SUFFIX = "/completions";
    private static final String MODELS_SUFFIX = "/models";

    private OpenAiModelCatalog() {
    }

    /**
     * Derives the model catalog URL from the chat-completions endpoint the user configured.
     *
     * <p>{@code https://host/v1}, {@code https://host/v1/chat/completions} and
     * {@code https://host/v1/} all resolve to {@code https://host/v1/models}. The endpoint is
     * validated with the same policy as translation requests, so a remote plaintext host is
     * rejected before any credential is sent.
     *
     * @param chatEndpoint configured chat-completions endpoint
     * @return absolute URL of the model catalog
     * @throws IllegalArgumentException when the endpoint is missing, unsafe, or malformed
     */
    public static String modelsEndpoint(String chatEndpoint) {
        URI endpoint = EndpointPolicy.requireSafeEndpoint(chatEndpoint);
        String raw = endpoint.toString();
        int fragment = raw.indexOf('#');
        if (fragment >= 0) {
            raw = raw.substring(0, fragment);
        }
        int query = raw.indexOf('?');
        String suffix = query < 0 ? "" : raw.substring(query);
        String base = query < 0 ? raw : raw.substring(0, query);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(CHAT_COMPLETIONS_SUFFIX)) {
            base = base.substring(0, base.length() - CHAT_COMPLETIONS_SUFFIX.length());
        } else if (base.endsWith(COMPLETIONS_SUFFIX)) {
            base = base.substring(0, base.length() - COMPLETIONS_SUFFIX.length());
        } else if (base.endsWith(MODELS_SUFFIX)) {
            return base + suffix;
        }
        String catalog = base + MODELS_SUFFIX + suffix;
        // Re-validate the derived URL so a crafted endpoint cannot smuggle another host in.
        EndpointPolicy.requireSafeEndpoint(catalog);
        return catalog;
    }

    /**
     * Parses model identifiers out of a catalog response.
     *
     * <p>Duplicate identifiers are collapsed, the result is sorted case-insensitively so the list
     * is stable between requests, and it is truncated to {@link #MAXIMUM_MODELS}. A malformed body
     * yields an empty list instead of an exception: the caller only needs to know that nothing
     * usable came back.
     *
     * @param json raw response body, possibly {@code null}
     * @return sorted, de-duplicated model identifiers, never {@code null}
     */
    public static List<String> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Object root;
        try {
            root = JsonStrings.parse(json);
        } catch (RuntimeException malformed) {
            return Collections.emptyList();
        }
        Set<String> identifiers = new LinkedHashSet<String>();
        collectModels(root, identifiers);
        if (identifiers.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> models = new ArrayList<String>(identifiers);
        Collections.sort(models, String.CASE_INSENSITIVE_ORDER);
        if (models.size() > MAXIMUM_MODELS) {
            return new ArrayList<String>(models.subList(0, MAXIMUM_MODELS));
        }
        return models;
    }

    /**
     * Fetches the catalog for a configured chat-completions endpoint.
     *
     * @param chatEndpoint configured chat-completions endpoint
     * @param apiKey API key to send as a bearer token, or {@code null}/empty for none
     * @return sorted, de-duplicated model identifiers, possibly empty
     * @throws IOException when the request fails or the server answers with an error status
     */
    public static List<String> fetch(String chatEndpoint, String apiKey) throws IOException {
        return fetch(chatEndpoint, apiKey, new HttpJsonClient(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS));
    }

    static List<String> fetch(String chatEndpoint, String apiKey, HttpJsonClient http) throws IOException {
        String key = apiKey == null ? "" : apiKey.trim();
        Map<String, String> headers = key.isEmpty()
                ? Collections.<String, String>emptyMap()
                : Collections.singletonMap("Authorization", "Bearer " + key);
        String response = http.get(URI.create(modelsEndpoint(chatEndpoint)), headers);
        return parse(response);
    }

    private static void collectModels(Object node, Set<String> identifiers) {
        if (node instanceof List) {
            for (Object entry : (List<?>) node) {
                addModel(entry, identifiers);
            }
            return;
        }
        if (!(node instanceof Map)) {
            return;
        }
        Map<?, ?> object = (Map<?, ?>) node;
        Object data = object.get("data");
        if (data instanceof List) {
            collectModels(data, identifiers);
            return;
        }
        Object models = object.get("models");
        if (models instanceof List) {
            collectModels(models, identifiers);
            return;
        }
        addModel(object, identifiers);
    }

    private static void addModel(Object entry, Set<String> identifiers) {
        if (entry instanceof String) {
            addIdentifier(identifiers, (String) entry);
            return;
        }
        if (!(entry instanceof Map)) {
            return;
        }
        Map<?, ?> object = (Map<?, ?>) entry;
        String[] keys = {"id", "name", "model"};
        for (String key : keys) {
            Object value = object.get(key);
            if (value instanceof String && addIdentifier(identifiers, (String) value)) {
                return;
            }
        }
    }

    private static boolean addIdentifier(Set<String> identifiers, String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty() && identifiers.add(trimmed);
    }
}
