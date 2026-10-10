package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationStreamListener;
import org.universaltranslator.core.net.HttpJsonClient;

/**
 * Azure OpenAI's deployment-scoped chat completions API.
 *
 * <p>Azure speaks the same chat-completions protocol as OpenAI but addresses it differently: the
 * model is the <em>deployment name</em> and appears in the path
 * ({@code {endpoint}/openai/deployments/{deployment}/chat/completions}), the {@code api-version}
 * query parameter is mandatory, and the credential travels in a bare {@code api-key} header rather
 * than as a bearer token. The request and response shapes are otherwise identical, so the work is
 * delegated to {@link OpenAiChatTranslationProvider} with those two differences applied — including
 * its batching, streaming and reasoning-budget behaviour.
 */
public final class AzureOpenAiTranslationProvider implements TranslationProvider {
    /** Placeholder host: the user replaces it with their own resource name. */
    public static final String DEFAULT_ENDPOINT = "https://YOUR-RESOURCE.openai.azure.com";
    /** Pinned so an unconfigured install sends a version Azure still serves. */
    public static final String DEFAULT_API_VERSION = "2024-10-21";
    private static final String DEPLOYMENTS_PATH = "/openai/deployments/";
    private static final String CHAT_PATH = "/chat/completions";
    private static final String VERSION_PARAMETER = "api-version=";

    private final String deployment;
    private final String requestUrl;
    private final OpenAiChatTranslationProvider delegate;

    public AzureOpenAiTranslationProvider(String endpoint, String apiKey, String deployment) {
        this(endpoint, apiKey, deployment, DEFAULT_API_VERSION);
    }

    public AzureOpenAiTranslationProvider(
            String endpoint,
            String apiKey,
            String deployment,
            String apiVersion
    ) {
        this(endpoint, apiKey, deployment, apiVersion, new HttpJsonClient(5000, 120000),
                TranslationPrompt.Settings.standard());
    }

    public AzureOpenAiTranslationProvider(
            String endpoint,
            String apiKey,
            String deployment,
            String apiVersion,
            TranslationPrompt.Settings prompt
    ) {
        this(endpoint, apiKey, deployment, apiVersion, new HttpJsonClient(5000, 120000), prompt);
    }

    AzureOpenAiTranslationProvider(
            String endpoint,
            String apiKey,
            String deployment,
            String apiVersion,
            HttpJsonClient http,
            TranslationPrompt.Settings prompt
    ) {
        this.deployment = requireText("Azure OpenAI deployment", deployment);
        this.requestUrl = compose(endpoint, this.deployment, apiVersion);
        this.delegate = new OpenAiChatTranslationProvider(
                requestUrl, apiKey, this.deployment, "azure-openai", http, prompt, "api-key", "");
    }

    /** The delegate's id already carries the provider name, the deployment and the prompt. */
    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        return delegate.translate(request);
    }

    @Override
    public String translateStreaming(TranslationRequest request, TranslationStreamListener listener)
            throws Exception {
        return delegate.translateStreaming(request, listener);
    }

    /**
     * Sends a one-token completion to prove the resource, the credential and the deployment.
     *
     * @return the raw response body
     * @throws Exception when the endpoint, the credential, the deployment or the response is unusable
     */
    public String probe() throws Exception {
        return delegate.probe();
    }

    /** The deployment-scoped URL this provider requests, with the api-version query filled in. */
    public String requestUrl() {
        return requestUrl;
    }

    /**
     * Builds the deployment-scoped URL.
     *
     * <p>A user who pasted a complete URL — one that already contains
     * {@code /openai/deployments/} — keeps it, and a version they pinned there is respected; only
     * the missing pieces are filled in. That way both the resource base URL the editor shows and a
     * full deployment URL work.
     *
     * @param endpoint configured resource endpoint, or {@code null}
     * @param deployment deployment name to address
     * @param apiVersion configured {@code api-version}, or {@code null} for the pinned default
     * @return the URL to request
     */
    static String compose(String endpoint, String deployment, String apiVersion) {
        String base = endpoint == null ? "" : endpoint.trim();
        if (base.isEmpty()) {
            base = DEFAULT_ENDPOINT;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String version = apiVersion == null || apiVersion.trim().isEmpty()
                ? DEFAULT_API_VERSION : apiVersion.trim();
        String url;
        if (base.contains(DEPLOYMENTS_PATH)) {
            url = base.endsWith(CHAT_PATH) || base.indexOf('?') >= 0 ? base : base + CHAT_PATH;
            if (url.contains(VERSION_PARAMETER)) {
                return url;
            }
        } else {
            url = base + DEPLOYMENTS_PATH + deployment + CHAT_PATH;
        }
        return url + (url.indexOf('?') < 0 ? "?" : "&") + VERSION_PARAMETER + version;
    }

    private static String requireText(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
