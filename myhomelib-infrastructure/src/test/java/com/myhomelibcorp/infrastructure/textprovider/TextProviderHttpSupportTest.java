package com.myhomelibcorp.infrastructure.textprovider;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.textprovider.TextProviderErrorKind;
import com.myhomelibcorp.application.textprovider.TextProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextProviderHttpSupportTest {
    private static final String PROPERTY = "myhomelib.test.textprovider.secret";

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void rejectsNonHttpsRemoteEndpoint() {
        assertThatThrownBy(() -> TextProviderHttpSupport.requireHttpsUri(
                "http://example.test/translate", "endpoint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void runtimeCredentialHasPriorityWithoutPersistingSecret() {
        MapSettings settings = new MapSettings();
        settings.put("provider.secret", "plaintext-must-not-be-used");
        System.setProperty(PROPERTY, "runtime-secret");

        String credential = TextProviderHttpSupport.credential(
                settings, "provider.secret", PROPERTY, "MYHOMELIB_UNUSED_TEST_SECRET");

        assertThat(credential).isEqualTo("runtime-secret");
    }

    @Test
    void plaintextPersistedCredentialIsRejected() {
        MapSettings settings = new MapSettings();
        settings.put("provider.secret", "plaintext-secret");

        assertThatThrownBy(() -> TextProviderHttpSupport.credential(
                settings, "provider.secret", PROPERTY, "MYHOMELIB_UNUSED_TEST_SECRET"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be encrypted");
    }


    @Test
    void commonHttpStatusPolicyMapsRateLimitAuthenticationTimeoutAndAvailability() {
        var messages = new TextProviderHttpSupport.FailureMessages(
                "auth rejected", "service unavailable", "request failed: ");

        assertKind(429, status -> false, messages, TextProviderErrorKind.RATE_LIMITED);
        assertKind(403, status -> status == 403, messages, TextProviderErrorKind.AUTHENTICATION);
        assertKind(408, status -> false, messages, TextProviderErrorKind.TIMEOUT);
        assertKind(504, status -> false, messages, TextProviderErrorKind.TIMEOUT);
        assertKind(503, status -> false, messages, TextProviderErrorKind.UNAVAILABLE);
        assertKind(422, status -> false, messages, TextProviderErrorKind.FAILED);
    }

    @Test
    void jsonObjectReaderRejectsMalformedJsonWithDedicatedMessage() {
        assertThatThrownBy(() -> TextProviderHttpSupport.readJsonObject(
                new ObjectMapper(), "{broken", "not an object", "invalid json"))
                .isInstanceOfSatisfying(TextProviderException.class, error -> {
                    assertThat(error.kind()).isEqualTo(TextProviderErrorKind.INVALID_RESPONSE);
                    assertThat(error).hasMessage("invalid json");
                    assertThat(error.getCause()).isNotNull();
                });
    }

    @Test
    void jsonObjectReaderRejectsNonObjectJsonWithoutReclassifyingItAsParseFailure() {
        assertThatThrownBy(() -> TextProviderHttpSupport.readJsonObject(
                new ObjectMapper(), "[]", "not an object", "invalid json"))
                .isInstanceOfSatisfying(TextProviderException.class, error -> {
                    assertThat(error.kind()).isEqualTo(TextProviderErrorKind.INVALID_RESPONSE);
                    assertThat(error).hasMessage("not an object");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void commonHttpStatusPolicyAcceptsEverySuccessful2xxStatus() throws Exception {
        var messages = new TextProviderHttpSupport.FailureMessages("auth", "unavailable", "failed: ");
        TextProviderHttpSupport.requireSuccessStatus(response(200), status -> false, messages);
        TextProviderHttpSupport.requireSuccessStatus(response(204), status -> false, messages);
        TextProviderHttpSupport.requireSuccessStatus(response(299), status -> false, messages);
    }

    private static void assertKind(
            int status,
            java.util.function.IntPredicate auth,
            TextProviderHttpSupport.FailureMessages messages,
            TextProviderErrorKind expected) {
        assertThatThrownBy(() -> TextProviderHttpSupport.requireSuccessStatus(response(status), auth, messages))
                .isInstanceOfSatisfying(TextProviderException.class, error ->
                        assertThat(error.kind()).isEqualTo(expected));
    }

    private static HttpResponse<String> response(int status) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() {
                return HttpRequest.newBuilder(URI.create("https://example.test")).build();
            }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
            @Override public String body() { return ""; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://example.test"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
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
}
