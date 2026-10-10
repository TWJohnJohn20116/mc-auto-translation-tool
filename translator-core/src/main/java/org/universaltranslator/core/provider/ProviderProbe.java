package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.net.HttpJsonClient;

import java.util.Locale;

/**
 * Probes the selected provider with the protocol it actually speaks.
 *
 * <p>The settings screens hold only the endpoint, the credential and the model of the provider the
 * user selected, and every one of them used to be probed with a hard-coded OpenAI-compatible chat
 * request. That is wrong for a provider whose protocol differs — DeepL reads a form and answers with
 * {@code translations[0].text}, and an OpenAI chat body posted to its endpoint always fails — so the
 * screens call this instead and the protocol lives with the provider that owns it.
 *
 * <p>The model may be empty: providers that do not need one ignore it, and the ones that do report
 * the missing value as an ordinary failure.
 */
public final class ProviderProbe {
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 30000;

    private ProviderProbe() {
    }

    /**
     * Sends one minimal request through {@code provider} and returns the raw response body.
     *
     * @param provider provider id from {@link org.universaltranslator.core.TranslationProviderCatalog}
     * @param endpoint endpoint currently shown in the settings screen
     * @param apiKey credential currently shown in the settings screen
     * @param model model, or Azure OpenAI deployment, currently shown in the settings screen
     * @return the raw response body
     * @throws Exception when the request fails, or the response is not what that protocol returns
     */
    public static String probe(String provider, String endpoint, String apiKey, String model)
            throws Exception {
        HttpJsonClient http = new HttpJsonClient(CONNECT_TIMEOUT_MILLIS, READ_TIMEOUT_MILLIS);
        TranslationPrompt.Settings prompt = TranslationPrompt.Settings.standard();
        String selected = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if ("deepl".equals(selected)) {
            return new DeepLTranslationProvider(endpoint, apiKey, model, http).probe();
        }
        if ("gemini".equals(selected)) {
            return new GeminiTranslationProvider(endpoint, apiKey, model, http, prompt).probe();
        }
        if ("claude".equals(selected)) {
            return new ClaudeTranslationProvider(endpoint, apiKey, model, http, prompt).probe();
        }
        if ("azure-openai".equals(selected)) {
            return new AzureOpenAiTranslationProvider(endpoint, apiKey, model,
                    AzureOpenAiTranslationProvider.DEFAULT_API_VERSION, http, prompt).probe();
        }
        return new OpenAiChatTranslationProvider(endpoint, apiKey, model, provider, http, prompt)
                .probe();
    }
}
