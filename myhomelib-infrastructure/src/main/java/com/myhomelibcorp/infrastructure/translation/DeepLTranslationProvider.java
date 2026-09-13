package com.myhomelibcorp.infrastructure.translation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import com.myhomelibcorp.application.translation.TranslationProvider;
import com.myhomelibcorp.application.translation.TranslationQuery;
import com.myhomelibcorp.application.translation.TranslationResult;
import com.myhomelibcorp.infrastructure.download.OnlineHttpPolicy;
import com.myhomelibcorp.infrastructure.textprovider.TextProviderHttpSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

@Component
public final class DeepLTranslationProvider implements TranslationProvider {
    public static final String PROVIDER_ID = "deepl";
    public static final String PROVIDER_NAME = "DeepL";
    private static final String DEFAULT_ENDPOINT = "https://api-free.deepl.com/v2/translate";

    private final ApplicationSettingsPort settings;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public DeepLTranslationProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, new OnlineHttpPolicy(settings).create(null));
    }

    DeepLTranslationProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper, HttpClient client) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override public String id() { return PROVIDER_ID; }
    @Override public String displayName() { return PROVIDER_NAME; }
    @Override public boolean isRemote() { return true; }
    @Override public boolean isEnabled() { return settings.getBoolean("translation.deepl.enabled", false); }

    @Override
    public TranslationResult translate(TranslationQuery query, TextProviderRequestContext context)
            throws TextProviderException {
        context.throwIfStopped();
        String key = apiKey();
        if (key.isBlank()) throw new TextProviderException(TextProviderErrorKind.AUTHENTICATION, "DeepL API key is not configured");

        URI endpoint = TextProviderHttpSupport.requireHttpsUri(
                settings.get("translation.deepl.endpoint", DEFAULT_ENDPOINT), "translation.deepl.endpoint");
        StringBuilder body = new StringBuilder()
                .append(TextProviderHttpSupport.formParam("text", query.text()))
                .append('&').append(TextProviderHttpSupport.formParam("target_lang", deeplLanguage(query.targetLanguage())));
        if (!query.sourceLanguage().isBlank()) {
            body.append('&').append(TextProviderHttpSupport.formParam("source_lang", deeplLanguage(query.sourceLanguage())));
        }
        Duration remaining = context.remainingTimeout();
        if (remaining.isZero()) throw TextProviderException.timeout();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(remaining)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Authorization", "DeepL-Auth-Key " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = TextProviderHttpSupport.send(client, request, context, PROVIDER_NAME);
        return parse(response, query);
    }

    private TranslationResult parse(HttpResponse<String> response, TranslationQuery query) throws TextProviderException {
        int status = response.statusCode();
        if (status == 429) throw TextProviderException.rateLimited(TextProviderHttpSupport.parseRetryAfter(response));
        if (status == 401 || status == 403 || status == 456) {
            throw new TextProviderException(TextProviderErrorKind.AUTHENTICATION, "DeepL request was rejected");
        }
        if (status == 408 || status == 504) throw TextProviderException.timeout();
        if (status >= 500) throw new TextProviderException(TextProviderErrorKind.UNAVAILABLE, "DeepL is unavailable");
        if (status < 200 || status >= 300) {
            throw new TextProviderException(TextProviderErrorKind.FAILED, "DeepL request failed with HTTP " + status);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.isObject()) {
                throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "DeepL returned an invalid response");
            }
            JsonNode item = root.path("translations").path(0);
            String text = item.path("text").asText("").trim();
            String source = item.path("detected_source_language").asText(query.sourceLanguage()).toLowerCase(Locale.ROOT);
            if (text.isBlank()) throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "DeepL response has no translation");
            return new TranslationResult(PROVIDER_ID, PROVIDER_NAME, source, query.targetLanguage(), text);
        } catch (JsonProcessingException invalidJson) {
            throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "DeepL returned invalid JSON", invalidJson);
        }
    }

    private String apiKey() {
        return TextProviderHttpSupport.credential(
                settings, "translation.deepl.apiKey", "myhomelib.translation.deepl.apiKey", "MYHOMELIB_DEEPL_API_KEY");
    }

    private static String deeplLanguage(String language) {
        return language.replace('_', '-').toUpperCase(Locale.ROOT);
    }
}
