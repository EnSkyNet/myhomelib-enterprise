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

class GoogleBooksMetadataProviderTest {
    private HttpServer server;
    private final AtomicReference<Handler> handler = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/books/v1/volumes", exchange -> {
            requests.incrementAndGet();
            Handler current = handler.get();
            if (current == null) respond(exchange, 500, "{}");
            else current.handle(exchange);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void queryUsesGoogleFieldOperatorsAndBoundsMaxResultsToForty() {
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));

        URI uri = provider.buildSearchUri(
                new MetadataQuery("9780132350884", "Clean Code", "Robert C. Martin", 100));
        Map<String, String> query = parseQuery(uri);

        assertThat(query.get("q"))
                .isEqualTo("isbn:9780132350884 intitle:\"Clean Code\" inauthor:\"Robert C. Martin\"");
        assertThat(query.get("maxResults")).isEqualTo("40");
        assertThat(query.get("orderBy")).isEqualTo("relevance");
        assertThat(query.get("printType")).isEqualTo("books");
        assertThat(query.get("projection")).isEqualTo("full");
        assertThat(query).doesNotContainKey("key");
    }

    @Test
    void mapsVolumeMetadataAndSourceAttribution() throws Exception {
        handler.set(exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key")).isEqualTo("test-key");
            respond(exchange, 200, """
                {
                  "totalItems":1,
                  "items":[{
                    "id":"volume-1",
                    "volumeInfo":{
                      "title":"Clean Code",
                      "authors":["Robert C. Martin"],
                      "publisher":"Prentice Hall",
                      "publishedDate":"2008-08-01",
                      "description":"A handbook of agile software craftsmanship.",
                      "industryIdentifiers":[
                        {"type":"ISBN_10","identifier":"0132350882"},
                        {"type":"ISBN_13","identifier":"9780132350884"}
                      ],
                      "language":"en",
                      "imageLinks":{"thumbnail":"https://books.example/cover.jpg"},
                      "infoLink":"https://books.example/volume-1"
                    }
                  }]
                }
                """);
        });
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));

        var result = provider.search(
                MetadataQuery.byIsbn("9780132350884"),
                MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false)));

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.source().providerId()).isEqualTo("google-books");
            assertThat(candidate.source().providerName()).isEqualTo("Google Books");
            assertThat(candidate.source().recordId()).isEqualTo("volume-1");
            assertThat(candidate.source().recordUrl()).isEqualTo("https://books.example/volume-1");
            assertThat(candidate.confidence()).isEqualTo(0.99);
            assertThat(candidate.title()).isEqualTo("Clean Code");
            assertThat(candidate.authors()).containsExactly("Robert C. Martin");
            assertThat(candidate.isbn()).isEqualTo("9780132350884");
            assertThat(candidate.year()).isEqualTo(2008);
            assertThat(candidate.publisher()).isEqualTo("Prentice Hall");
            assertThat(candidate.language()).isEqualTo("en");
            assertThat(candidate.annotation()).contains("agile software craftsmanship");
            assertThat(candidate.coverUrl()).isEqualTo("https://books.example/cover.jpg");
        });
    }

    @Test
    void isbn10AndIsbn13AreMatchedAsEquivalent() throws Exception {
        handler.set(exchange -> respond(exchange, 200, """
                {"totalItems":1,"items":[{
                  "id":"volume-2",
                  "volumeInfo":{
                    "title":"Clean Code",
                    "authors":["Robert C. Martin"],
                    "industryIdentifiers":[{"type":"ISBN_10","identifier":"0132350882"}]
                  }
                }]}
                """));
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));

        var result = provider.search(
                MetadataQuery.byIsbn("9780132350884"),
                MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false)));

        assertThat(result).singleElement().satisfies(candidate -> {
            assertThat(candidate.isbn()).isEqualTo("9780132350884");
            assertThat(candidate.confidence()).isEqualTo(0.99);
        });
    }

    @Test
    void relevanceRankPlusExactTitleAndAuthorProduceDeterministicConfidence() throws Exception {
        handler.set(exchange -> respond(exchange, 200, """
                {"totalItems":2,"items":[
                  {"id":"first","volumeInfo":{"title":"Dune","authors":["Frank Herbert"]}},
                  {"id":"second","volumeInfo":{"title":"Dune Messiah","authors":["Frank Herbert"]}}
                ]}
                """));
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));

        var result = provider.search(
                new MetadataQuery("", "Dune", "Frank Herbert", 20),
                MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).source().recordId()).isEqualTo("first");
        assertThat(result.get(0).confidence()).isEqualTo(0.98);
        assertThat(result.get(1).confidence()).isLessThan(result.get(0).confidence());
    }

    @Test
    void zeroResultsWithoutItemsIsValidEmptyResponse() throws Exception {
        handler.set(exchange -> respond(exchange, 200, "{\"totalItems\":0}"));
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));

        var result = provider.search(
                MetadataQuery.byTitle("no such book"),
                MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false)));

        assertThat(result).isEmpty();
    }

    @Test
    void cachesResultsAndAvoidsSecondHttpCall() throws Exception {
        handler.set(exchange -> respond(exchange, 200, """
                {"totalItems":1,"items":[{"id":"cached","volumeInfo":{"title":"Dune"}}]}
                """));
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));
        MetadataQuery query = MetadataQuery.byTitle("Dune");

        assertThat(provider.search(query, context())).hasSize(1);
        assertThat(provider.search(query, context())).hasSize(1);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void missingApiKeyIsAuthenticationFailureBeforeNetworkCall() {
        GoogleBooksMetadataProvider provider = provider(settings("", true));

        assertThatThrownBy(() -> provider.search(MetadataQuery.byTitle("Dune"), context()))
                .isInstanceOf(MetadataProviderException.class)
                .satisfies(error -> assertThat(((MetadataProviderException) error).kind())
                        .isEqualTo(MetadataProviderErrorKind.AUTHENTICATION));
        assertThat(requests.get()).isZero();
    }

    @Test
    void providerCanBeDisabledByConfiguration() {
        GoogleBooksMetadataProvider provider = provider(settings("test-key", false));
        assertThat(provider.isEnabled()).isFalse();
    }

    @Test
    void rateLimitIsGenericAndDoesNotLeakRemoteBody() {
        handler.set(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "9");
            respond(exchange, 429, "secret quota detail from remote service");
        });
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));

        assertThatThrownBy(() -> provider.search(MetadataQuery.byTitle("Dune"), context()))
                .isInstanceOf(MetadataProviderException.class)
                .satisfies(error -> {
                    MetadataProviderException providerError = (MetadataProviderException) error;
                    assertThat(providerError.kind()).isEqualTo(MetadataProviderErrorKind.RATE_LIMITED);
                    assertThat(providerError.retryAfter()).isEqualTo(Duration.ofSeconds(9));
                    assertThat(providerError.getMessage()).doesNotContain("secret quota");
                });
    }

    @Test
    void malformedJsonIsInvalidResponse() {
        handler.set(exchange -> respond(exchange, 200, "{not-json"));
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));

        assertThatThrownBy(() -> provider.search(MetadataQuery.byTitle("Dune"), context()))
                .isInstanceOf(MetadataProviderException.class)
                .satisfies(error -> assertThat(((MetadataProviderException) error).kind())
                        .isEqualTo(MetadataProviderErrorKind.INVALID_RESPONSE));
    }

    @Test
    void serverFailureIsUnavailableWithoutRemotePayloadLeak() {
        handler.set(exchange -> respond(exchange, 503, "internal service details"));
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));

        assertThatThrownBy(() -> provider.search(MetadataQuery.byTitle("Dune"), context()))
                .isInstanceOf(MetadataProviderException.class)
                .satisfies(error -> {
                    MetadataProviderException providerError = (MetadataProviderException) error;
                    assertThat(providerError.kind()).isEqualTo(MetadataProviderErrorKind.UNAVAILABLE);
                    assertThat(providerError.getMessage()).doesNotContain("internal service details");
                });
    }

    @Test
    void cooperativeCancellationAbortsInFlightRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        handler.set(exchange -> {
            received.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"totalItems\":0}");
        });
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));
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
    void requestDeadlineMapsToTimeout() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        handler.set(exchange -> {
            received.countDown();
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"totalItems\":0}");
        });
        GoogleBooksMetadataProvider provider = provider(settings("test-key", true));

        assertThatThrownBy(() -> provider.search(
                MetadataQuery.byTitle("Dune"),
                MetadataRequestContext.create(Duration.ofMillis(100), new AtomicBoolean(false))))
                .isInstanceOf(MetadataProviderException.class)
                .satisfies(error -> assertThat(((MetadataProviderException) error).kind())
                        .isEqualTo(MetadataProviderErrorKind.TIMEOUT));
        assertThat(received.getCount()).isZero();
    }

    private GoogleBooksMetadataProvider provider(ApplicationSettingsPort settings) {
        return new GoogleBooksMetadataProvider(
                settings,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                baseUri().resolve("books/v1/volumes"),
                baseUri().resolve("books"));
    }

    private ApplicationSettingsPort settings(String apiKey, boolean enabled) {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.get("online.proxy.mode", "SYSTEM")).thenReturn("NONE");
        when(settings.get("online.userAgent", "MyHomeLib Enterprise/7.1")).thenReturn("MyHomeLib Enterprise/7.1");
        when(settings.get("metadata.googleBooks.apiKey", "")).thenReturn(apiKey);
        when(settings.get("metadata.googleBooks.enabled", "false")).thenReturn(Boolean.toString(enabled));
        when(settings.getInt("metadata.googleBooks.requestsPerSecond", 5)).thenReturn(20);
        return settings;
    }

    private MetadataRequestContext context() {
        return MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false));
    }

    private URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
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
