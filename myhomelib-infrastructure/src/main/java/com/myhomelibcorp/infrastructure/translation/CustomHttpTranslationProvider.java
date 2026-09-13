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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Generic opt-in HTTPS translation adapter for self-hosted/custom providers.
 * Request JSON: {text, sourceLanguage, targetLanguage}; response JSON requires "translation".
 */
@Component
public final class CustomHttpTranslationProvider implements TranslationProvider {
    public static final String PROVIDER_ID = "custom-translation";
    public static final String PROVIDER_NAME = "Custom translation service";

    private final ApplicationSettingsPort settings;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    @Autowired
    public CustomHttpTranslationProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, new OnlineHttpPolicy(settings).create(null));
    }

    CustomHttpTranslationProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper, HttpClient client) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override public String id() { return PROVIDER_ID; }
    @Override public String displayName() { return PROVIDER_NAME; }
    @Override public boolean isRemote() { return true; }
    @Override public boolean isEnabled() {
        if (!settings.getBoolean("translation.custom.enabled", false)) return false;
        try {
            TextProviderHttpSupport.requireHttpsUri(
                    settings.get("translation.custom.endpoint", ""), "translation.custom.endpoint");
            return true;
        } catch (RuntimeException invalidEndpoint) {
            return false;
        }
    }

    @Override
    public TranslationResult translate(TranslationQuery query, TextProviderRequestContext context)
            throws TextProviderException {
        context.throwIfStopped();
        URI endpoint = TextProviderHttpSupport.requireHttpsUri(
                settings.get("translation.custom.endpoint", ""), "translation.custom.endpoint");
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("text", query.text());
        payload.put("sourceLanguage", query.sourceLanguage());
        payload.put("targetLanguage", query.targetLanguage());
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            throw new TextProviderException(TextProviderErrorKind.FAILED, "Custom translation request could not be encoded", impossible);
        }
        Duration remaining = context.remainingTimeout();
        if (remaining.isZero()) throw TextProviderException.timeout();
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(remaining)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=UTF-8");
        String token = TextProviderHttpSupport.credential(
                settings, "translation.custom.token", "myhomelib.translation.custom.token", "MYHOMELIB_CUSTOM_TRANSLATION_TOKEN");
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = TextProviderHttpSupport.send(
                client,
                request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                context,
                PROVIDER_NAME);
        return parse(response, query);
    }

    private TranslationResult parse(HttpResponse<String> response, TranslationQuery query) throws TextProviderException {
        int status = response.statusCode();
        if (status == 429) throw TextProviderException.rateLimited(TextProviderHttpSupport.parseRetryAfter(response));
        if (status == 401 || status == 403) throw new TextProviderException(TextProviderErrorKind.AUTHENTICATION, "Custom translation request was rejected");
        if (status == 408 || status == 504) throw TextProviderException.timeout();
        if (status >= 500) throw new TextProviderException(TextProviderErrorKind.UNAVAILABLE, "Custom translation service is unavailable");
        if (status < 200 || status >= 300) {
            throw new TextProviderException(TextProviderErrorKind.FAILED, "Custom translation request failed with HTTP " + status);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.isObject()) {
                throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "Custom translation service returned an invalid response");
            }
            String text = root.path("translation").asText("").trim();
            String source = root.path("sourceLanguage").asText(query.sourceLanguage()).trim();
            if (text.isBlank()) throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "Custom translation response has no translation");
            return new TranslationResult(PROVIDER_ID, PROVIDER_NAME, source, query.targetLanguage(), text);
        } catch (JsonProcessingException invalidJson) {
            throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "Custom translation service returned invalid JSON", invalidJson);
        }
    }
}
