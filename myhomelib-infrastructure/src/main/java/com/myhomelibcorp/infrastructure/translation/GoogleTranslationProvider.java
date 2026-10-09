package com.myhomelibcorp.infrastructure.translation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import com.myhomelibcorp.application.translation.TranslationProvider;
import com.myhomelibcorp.application.translation.TranslationQuery;
import com.myhomelibcorp.application.translation.TranslationResult;
import com.myhomelibcorp.infrastructure.download.OnlineHttpClientProvider;
import com.myhomelibcorp.infrastructure.textprovider.TextProviderHttpSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Objects;

@Component
public final class GoogleTranslationProvider implements TranslationProvider {
    public static final String PROVIDER_ID = "google-translate";
    public static final String PROVIDER_NAME = "Google Cloud Translation";
    private static final String DEFAULT_ENDPOINT = "https://translation.googleapis.com/language/translate/v2";

    private final ApplicationSettingsPort settings;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public GoogleTranslationProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, new OnlineHttpClientProvider(settings).shared());
    }

    @Autowired
    public GoogleTranslationProvider(ApplicationSettingsPort settings, ObjectMapper objectMapper,
                 OnlineHttpClientProvider httpClients) {
        this(settings, objectMapper, Objects.requireNonNull(httpClients, "httpClients").shared());
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
        HttpResponse<String> response = TextProviderHttpSupport.postForm(
                client, endpoint, body.toString(),
                Map.of("X-Goog-Api-Key", key),
                context, PROVIDER_NAME);
        return parse(response, query);
    }

    private TranslationResult parse(HttpResponse<String> response, TranslationQuery query) throws TextProviderException {
        TextProviderHttpSupport.requireSuccessStatus(
                response,
                status -> status == 400 || status == 401 || status == 403,
                new TextProviderHttpSupport.FailureMessages(
                        "Google Translation request was rejected",
                        "Google Translation is unavailable",
                        "Google Translation request failed with HTTP "));
        JsonNode root = TextProviderHttpSupport.readJsonObject(
                objectMapper, response.body(), "Google Translation returned an invalid response", "Google Translation returned invalid JSON");
        JsonNode item = root.path("data").path("translations").path(0);
        String text = item.path("translatedText").asString("").trim();
        String source = item.path("detectedSourceLanguage").asString(query.sourceLanguage()).trim();
        if (text.isBlank()) throw new TextProviderException(TextProviderErrorKind.INVALID_RESPONSE, "Google Translation response has no translation");
        return new TranslationResult(PROVIDER_ID, PROVIDER_NAME, source, query.targetLanguage(), text);
    }

    private String apiKey() {
        return TextProviderHttpSupport.credential(
                settings, "translation.google.apiKey", "myhomelib.translation.google.apiKey", "MYHOMELIB_GOOGLE_TRANSLATE_API_KEY");
    }
}
