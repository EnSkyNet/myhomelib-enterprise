package com.myhomelibcorp.infrastructure.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.metadata.MetadataProviderErrorKind;
import com.myhomelibcorp.application.metadata.MetadataProviderException;
import com.myhomelibcorp.application.metadata.MetadataQuery;
import com.myhomelibcorp.application.metadata.MetadataRequestContext;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenLibraryMetadataProviderTest {
    private HttpServer server;
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search.json", exchange -> {
            requests.incrementAndGet();
            try {
                Handler current = handler.get();
                if (current == null) respond(exchange, 500, "{}");
                else current.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void mapsIsbnResultWithAttributionCoverAndIdentifiedUserAgent() throws Exception {
        AtomicReference<Map<String, String>> queryParams = new AtomicReference<>();
        AtomicReference<String> userAgent = new AtomicReference<>();
        handler.set(exchange -> {
            queryParams.set(parseQuery(exchange.getRequestURI()));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, """
                    {"numFound":1,"docs":[{
                      "key":"/works/OL45804W",
                      "title":"Clean Code",
                      "author_name":["Robert C. Martin"],
                      "first_publish_year":2008,
                      "isbn":["0132350882","9780132350884"],
                      "publisher":["Prentice Hall"],
                      "language":["eng"],
                      "cover_i":12345
                    }]}
                    """);
        });
        OpenLibraryMetadataProvider provider = provider(settings("dev@example.org"));

        var result = provider.search(
                MetadataQuery.byIsbn("978-0-13-235088-4"),
                MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false)));

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.source().providerId()).isEqualTo("open-library");
            assertThat(candidate.source().providerName()).isEqualTo("Open Library");
            assertThat(candidate.source().recordId()).isEqualTo("/works/OL45804W");
            assertThat(candidate.source().recordUrl()).isEqualTo(baseUri().resolve("works/OL45804W").toString());
            assertThat(candidate.title()).isEqualTo("Clean Code");
            assertThat(candidate.authors()).containsExactly("Robert C. Martin");
            assertThat(candidate.isbn()).isEqualTo("9780132350884");
            assertThat(candidate.year()).isEqualTo(2008);
            assertThat(candidate.publisher()).isEqualTo("Prentice Hall");
            assertThat(candidate.language()).isEqualTo("eng");
            assertThat(candidate.coverUrl()).isEqualTo(coverBaseUri().resolve("12345-M.jpg").toString());
            assertThat(candidate.confidence()).isEqualTo(0.99);
        });
        assertThat(queryParams.get())
                .containsEntry("isbn", "9780132350884")
                .containsEntry("limit", "20")
                .containsKey("fields");
        assertThat(userAgent.get()).contains("MyHomeLib Enterprise/8.0.0").contains("dev@example.org");
    }

    @Test
    void titleAndAuthorQueryIsEncodedAndConfidenceIsDeterministic() throws Exception {
        AtomicReference<Map<String, String>> params = new AtomicReference<>();
        handler.set(exchange -> {
            params.set(parseQuery(exchange.getRequestURI()));
            respond(exchange, 200, """
                    {"docs":[{
                      "key":"/works/OL1W",
                      "title":"Майстер і Маргарита",
                      "author_name":["Михайло Булгаков"],
                      "first_publish_year":1967,
                      "isbn":["9780140455465"]
                    }]}
                    """);
        });
        OpenLibraryMetadataProvider provider = provider(settings(""));
        MetadataQuery query = new MetadataQuery("", "Майстер і Маргарита", "Михайло Булгаков", 7);

        var result = provider.search(
                query,
                MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false)));

        assertThat(params.get())
                .containsEntry("title", "Майстер і Маргарита")
                .containsEntry("author", "Михайло Булгаков")
                .containsEntry("limit", "7");
        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.confidence()).isEqualTo(0.97);
            assertThat(candidate.isbn()).isEqualTo("9780140455465");
        });
    }

    @Test
    void isbn10AndIsbn13AreMatchedAsEquivalent() throws Exception {
        handler.set(exchange -> respond(exchange, 200, """
                {"docs":[{
                  "key":"/works/OL2W",
                  "title":"Clean Code",
                  "author_name":["Robert C. Martin"],
                  "isbn":["0132350882"]
                }]}
                """));
        OpenLibraryMetadataProvider provider = provider(settings(""));

        var result = provider.search(
                MetadataQuery.byIsbn("9780132350884"),
                MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false)));

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.isbn()).isEqualTo("9780132350884");
            assertThat(candidate.confidence()).isEqualTo(0.99);
        });
    }

    @Test
    void cachesNormalizedResultsAndAvoidsSecondHttpCall() throws Exception {
        handler.set(exchange -> respond(exchange, 200, """
                {"docs":[{"key":"/works/OL3W","title":"Dune","author_name":["Frank Herbert"]}]}
                """));
        OpenLibraryMetadataProvider provider = provider(settings(""));
        MetadataQuery query = MetadataQuery.byTitle("Dune");
        MetadataRequestContext first = MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false));
        MetadataRequestContext second = MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false));

        assertThat(provider.search(query, first)).hasSize(1);
        assertThat(provider.search(query, second)).hasSize(1);

        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void mapsRateLimitWithoutLeakingRemoteBodyAndPreservesGenericRetryHint() {
        handler.set(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "12");
            respond(exchange, 429, "vendor secret quota payload");
        });
        OpenLibraryMetadataProvider provider = provider(settings(""));

        assertThatThrownBy(() -> provider.search(
                MetadataQuery.byTitle("Dune"),
                MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false))))
                .isInstanceOf(MetadataProviderException.class)
                .satisfies(error -> {
                    MetadataProviderException providerError = (MetadataProviderException) error;
                    assertThat(providerError.kind()).isEqualTo(MetadataProviderErrorKind.RATE_LIMITED);
                    assertThat(providerError.retryAfter()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(providerError.getMessage()).doesNotContain("vendor secret");
                });
    }

    @Test
    void malformedJsonIsInvalidResponse() {
        handler.set(exchange -> respond(exchange, 200, "{not-json"));
        OpenLibraryMetadataProvider provider = provider(settings(""));

        assertThatThrownBy(() -> provider.search(
                MetadataQuery.byTitle("Dune"),
                MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false))))
                .isInstanceOf(MetadataProviderException.class)
                .satisfies(error -> assertThat(((MetadataProviderException) error).kind())
                        .isEqualTo(MetadataProviderErrorKind.INVALID_RESPONSE));
    }

    @Test
    void cooperativeCancellationAbortsAnInFlightRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        handler.set(exchange -> {
            received.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"docs\":[]}");
        });
        OpenLibraryMetadataProvider provider = provider(settings(""));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    provider.search(
                            MetadataQuery.byTitle("Dune"),
                            MetadataRequestContext.create(Duration.ofSeconds(3), cancelled));
                    throw new AssertionError("expected cancellation");
                } catch (MetadataProviderException error) {
                    if (error.kind() != MetadataProviderErrorKind.CANCELLED) throw new AssertionError(error);
                }
            });
            assertThat(received.await(1, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            future.get(1, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void requestContextDeadlineMapsToTimeout() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        handler.set(exchange -> {
            received.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"docs\":[]}");
        });
        OpenLibraryMetadataProvider provider = provider(settings(""));

        assertThatThrownBy(() -> provider.search(
                MetadataQuery.byTitle("Dune"),
                MetadataRequestContext.create(Duration.ofMillis(100), new AtomicBoolean(false))))
                .isInstanceOf(MetadataProviderException.class)
                .satisfies(error -> assertThat(((MetadataProviderException) error).kind())
                        .isEqualTo(MetadataProviderErrorKind.TIMEOUT));
        assertThat(received.getCount()).isZero();
    }

    @Test
    void enabledFlagIsConfigurationDriven() {
        ApplicationSettingsPort settings = settings("");
        when(settings.get("metadata.openLibrary.enabled", "true")).thenReturn("false");
        OpenLibraryMetadataProvider provider = provider(settings);

        assertThat(provider.isEnabled()).isFalse();
    }

    private OpenLibraryMetadataProvider provider(ApplicationSettingsPort settings) {
        return new OpenLibraryMetadataProvider(
                settings,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                baseUri().resolve("search.json"),
                baseUri(),
                coverBaseUri());
    }

    private ApplicationSettingsPort settings(String contact) {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.get("online.proxy.mode", "SYSTEM")).thenReturn("NONE");
        when(settings.get("online.userAgent", "MyHomeLib Enterprise/8.0.0")).thenReturn("MyHomeLib Enterprise/8.0.0");
        when(settings.get("metadata.openLibrary.contact", "")).thenReturn(contact);
        return settings;
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private URI coverBaseUri() {
        return baseUri().resolve("covers/");
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) return params;
        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            params.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
