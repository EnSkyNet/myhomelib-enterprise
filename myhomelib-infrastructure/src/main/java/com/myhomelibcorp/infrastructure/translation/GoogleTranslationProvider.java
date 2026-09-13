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
import java.util.Objects;

@Component
public final class GoogleTranslationProvider implements TranslationProvider {
    public static final String PROVIDER_ID = "google-translate";
    public static final String PROVIDER_NAME = "Google Cloud Translation";
    private static final String DEFAULT_ENDPOINT = "https://translation.googleapis.com/language/translate/v2";

    private final ApplicationSettingsPort settings;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public GoogleTranslationProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, new OnlineHttpPolicy(settings).create(null));
    }

    GoogleTranslationProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper, HttpClient client) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override public String id() { return PROVIDER_ID; }
    @Override public String displayName() { return PROVIDER_NAME; }
    @Override public boolean isRemote() { return true; }
    @Override public boolean isEnabled() { return settings.getBoolean("translation.google.enabled", false); }

    @Override
    public TranslationResult translate(TranslationQuery query, TextProviderRequestContext context)
            throws TextProviderException {
        context.throwIfStopped();
        String key = apiKey();
        if (key.isBlank()) throw new TextProviderException(TextProviderErrorKind.AUTHENTICATION, "Google Translation API key is not configured");
        URI endpoint = TextProviderHttpSupport.requireHttpsUri(
                settings.get("translation.google.endpoint", DEFAULT_ENDPOINT), "translation.google.endpoint");

        StringBuilder body = new StringBuilder()
                .append(TextProviderHttpSupport.formParam("q", query.text()))
                .append('&').append(TextProviderHttpSupport.formParam("target", query.targetLanguage()))
                .append('&').append(TextProviderHttpSupport.formParam("format", "text"));
        if (!query.sourceLanguage().isBlank()) {
            body.append('&').append(TextProviderHttpSupport.formParam("source", query.sourceLanguage()));
        }
        Duration remaining = context.remainingTimeout();
        if (remaining.isZero()) throw TextProviderException.timeout();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(remaining)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Goog-Api-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = TextProviderHttpSupport.send(client, request, context, PROVIDER_NAME);
        return parse(response, query);
    }

    private TranslationResult parse(HttpResponse<String> response, TranslationQuery query) throws TextProviderException {
        int status = response.statusCode();
        if (status == 429) throw TextProviderException.rateLimited(TextProviderHttpSupport.parseRetryAfter(response));
        if (status == 400 || status == 401 || status == 403) {
            throw new TextProviderException(TextProviderErrorKind.AUTHENTICATION, "Google Translation request was rejected");
        }
        if (status == 408 || status == 504) throw TextProviderException.timeout();
        if (status >= 500) throw new TextProviderException(TextProviderErrorKind.UNAVAILABLE, "Google Translation is unavailable");
        if (status < 200 || status >= 300) {
            throw new TextProviderException(TextProviderErrorKind.FAILED, "Google Translation request failed with HTTP " + status);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.isObject()) {
                throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "Google Translation returned an invalid response");
            }
            JsonNode item = root.path("data").path("translations").path(0);
            String text = item.path("translatedText").asText("").trim();
            String source = item.path("detectedSourceLanguage").asText(query.sourceLanguage()).trim();
            if (text.isBlank()) throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "Google Translation response has no translation");
            return new TranslationResult(PROVIDER_ID, PROVIDER_NAME, source, query.targetLanguage(), text);
        } catch (JsonProcessingException invalidJson) {
            throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "Google Translation returned invalid JSON", invalidJson);
        }
    }

    private String apiKey() {
        return TextProviderHttpSupport.credential(
                settings, "translation.google.apiKey", "myhomelib.translation.google.apiKey", "MYHOMELIB_GOOGLE_TRANSLATE_API_KEY");
    }
}
