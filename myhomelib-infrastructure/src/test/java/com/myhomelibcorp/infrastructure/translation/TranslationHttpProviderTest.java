package com.myhomelibcorp.infrastructure.translation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.textprovider.TextProviderRequestContext;
import com.myhomelibcorp.application.translation.TranslationQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TranslationHttpProviderTest {
    private static final String DEEPL_PROPERTY = "myhomelib.translation.deepl.apiKey";
    private static final String GOOGLE_PROPERTY = "myhomelib.translation.google.apiKey";

    @AfterEach
    void clearProperties() {
        System.clearProperty(DEEPL_PROPERTY);
        System.clearProperty(GOOGLE_PROPERTY);
    }

    @Test
    void deepLAdapterUsesSpiAttributionAndHeaderCredential() throws Exception {
        System.setProperty(DEEPL_PROPERTY, "deepl-test-key");
        MapSettings settings = new MapSettings();
        settings.putBoolean("translation.deepl.enabled", true);
        settings.put("translation.deepl.endpoint", "https://example.test/deepl");
        StubHttpClient http = new StubHttpClient(200,
                "{\"translations\":[{\"detected_source_language\":\"UK\",\"text\":\"book\"}]}");
        DeepLTranslationProvider provider = new DeepLTranslationProvider(settings, new ObjectMapper(), http);

        var result = provider.translate(
                TranslationQuery.autoDetect("книга", "en"), context());

        assertThat(provider.isEnabled()).isTrue();
        assertThat(result.providerId()).isEqualTo(DeepLTranslationProvider.PROVIDER_ID);
        assertThat(result.translatedText()).isEqualTo("book");
        assertThat(http.lastRequest.get().headers().firstValue("Authorization"))
                .contains("DeepL-Auth-Key deepl-test-key");
        assertThat(http.lastRequest.get().uri().toString()).doesNotContain("deepl-test-key");
        assertThat(http.lastRequest.get().method()).isEqualTo("POST");
    }

    @Test
    void googleAdapterUsesHeaderCredentialAndParsesDetectedLanguage() throws Exception {
        System.setProperty(GOOGLE_PROPERTY, "google-test-key");
        MapSettings settings = new MapSettings();
        settings.putBoolean("translation.google.enabled", true);
        settings.put("translation.google.endpoint", "https://example.test/google");
        StubHttpClient http = new StubHttpClient(200,
                "{\"data\":{\"translations\":[{\"translatedText\":\"book\",\"detectedSourceLanguage\":\"uk\"}]}}");
        GoogleTranslationProvider provider = new GoogleTranslationProvider(settings, new ObjectMapper(), http);

        var result = provider.translate(TranslationQuery.autoDetect("книга", "en"), context());

        assertThat(result.providerId()).isEqualTo(GoogleTranslationProvider.PROVIDER_ID);
        assertThat(result.sourceLanguage()).isEqualTo("uk");
        assertThat(http.lastRequest.get().headers().firstValue("X-Goog-Api-Key"))
                .contains("google-test-key");
        assertThat(http.lastRequest.get().uri().toString()).doesNotContain("google-test-key");
    }

    @Test
    void customAdapterIsOptInAndRequiresHttpsEndpoint() throws Exception {
        MapSettings settings = new MapSettings();
        settings.putBoolean("translation.custom.enabled", true);
        settings.put("translation.custom.endpoint", "https://example.test/custom");
        StubHttpClient http = new StubHttpClient(200,
                "{\"translation\":\"book\",\"sourceLanguage\":\"uk\"}");
        CustomHttpTranslationProvider provider = new CustomHttpTranslationProvider(settings, new ObjectMapper(), http);

        var result = provider.translate(TranslationQuery.autoDetect("книга", "en"), context());

        assertThat(provider.isEnabled()).isTrue();
        assertThat(result.translatedText()).isEqualTo("book");
        assertThat(http.lastRequest.get().uri()).isEqualTo(URI.create("https://example.test/custom"));
        assertThat(http.lastRequest.get().headers().firstValue("Content-Type"))
                .contains("application/json; charset=UTF-8");
    }

    private static TextProviderRequestContext context() {
        return TextProviderRequestContext.create(Duration.ofSeconds(2), null);
    }

    private static final class MapSettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }

    private static final class StubHttpClient extends HttpClient {
        private final int status;
        private final String body;
        private final AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();

        private StubHttpClient(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException, InterruptedException {
            return response(request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(response(request));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.completedFuture(response(request));
        }

        @SuppressWarnings("unchecked")
        private <T> HttpResponse<T> response(HttpRequest request) {
            lastRequest.set(request);
            T responseBody = (T) body;
            return new HttpResponse<>() {
                @Override public int statusCode() { return status; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
                @Override public T body() { return responseBody; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
    }
}
