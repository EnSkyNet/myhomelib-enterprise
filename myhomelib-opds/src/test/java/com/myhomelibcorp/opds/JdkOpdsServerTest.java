package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.*;
import com.myhomelibcorp.application.port.out.opds.OpdsCatalogQueryPort;
import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.dto.ContinueReadingItemDto;
import com.myhomelibcorp.application.usecase.reading.ContinueReadingService;
import com.myhomelibcorp.application.webreader.WebReaderChapter;
import com.myhomelibcorp.application.webreader.WebReaderDocument;
import com.myhomelibcorp.application.webreader.WebReaderUseCase;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JdkOpdsServerTest {
    private static final String TLS_PASSWORD_PROPERTY = "myhomelib.opds.tls.keyStorePassword";
    private JdkOpdsServer server;

    @AfterEach
    void cleanup() {
        System.clearProperty(TLS_PASSWORD_PROPERTY);
        if (server != null) server.stop();
    }

    @Test
    void servesRootAndBoundedAuthorsWithoutJavaFx() throws Exception {
        var catalog = new OpdsCatalogService(new FakeCatalog());
        server = new JdkOpdsServer(catalog, null);
        int port = freePort();
        var status = server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false));

        assertThat(status.running()).isTrue();
        assertThat(status.exposedBeyondLocalhost()).isFalse();
        assertThat(status.baseUrl()).startsWith("http://");
        assertThat(status.healthUrl()).isEqualTo("http://127.0.0.1:" + port + "/health");

        var client = HttpClient.newHttpClient();
        var root = get(client, "http://127.0.0.1:" + port + "/opds");
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(root.body()).contains("Автори", "/opds/series", "/opds/genres", "/opds/search");

        var authors = get(client, "http://127.0.0.1:" + port + "/opds/authors?limit=1");
        assertThat(authors.statusCode()).isEqualTo(200);
        assertThat(authors.body()).contains("Автор Один", "2 книг").doesNotContain("Автор Два");
    }


    @Test
    void servesOpds2NavigationLibrarySearchGroupsFavoritesAndContinueReading() throws Exception {
        var catalog = new OpdsCatalogService(new FakeCatalog());
        server = new JdkOpdsServer(catalog, null);
        int port = freePort();
        assertThat(server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false)).running()).isTrue();

        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;

        var root = get(client, base + "/opds/v2");
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(root.headers().firstValue("Content-Type").orElse("")).startsWith("application/opds+json");
        assertThat(root.body()).contains("\"navigation\"", "/opds/v2/library", "/opds/v2/search?q=",
                "/opds/v2/collections", "/opds/v2/groups", "/opds/v2/favorites", "/opds/v2/continue");

        var library = get(client, base + "/opds/v2/library?limit=1");
        assertThat(library.statusCode()).isEqualTo(200);
        assertThat(library.body()).contains("\"publications\"", "Книга \\\"Один\\\"",
                "urn:myhomelib:book:b1", "\"next\"", "offset=1&limit=1");

        var search = get(client, base + "/opds/v2/search?q=needle&limit=1");
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(search.body()).contains("Пошук: needle", "q=needle", "Книга \\\"Один\\\"");

        var collections = get(client, base + "/opds/v2/collections");
        assertThat(collections.body()).contains("Основна колекція", "/opds/v2/collections/c1", "numberOfItems\":2");

        var groups = get(client, base + "/opds/v2/groups?limit=1");
        assertThat(groups.body()).contains("Обране", "/opds/v2/groups/1", "\"next\"");

        var groupBooks = get(client, base + "/opds/v2/groups/1");
        assertThat(groupBooks.body()).contains("Книга \\\"Один\\\"");

        var favorites = get(client, base + "/opds/v2/favorites");
        assertThat(favorites.body()).contains("Книга \\\"Один\\\"");

        var reading = get(client, base + "/opds/v2/continue");
        assertThat(reading.body()).contains("Книга \\\"Один\\\"");

        var detail = get(client, base + "/opds/v2/books/b1");
        assertThat(detail.body()).contains("application/fb2+xml", "/opds/download/b1");

        // OPDS 1.x remains available for old clients.
        var legacy = get(client, base + "/opds");
        assertThat(legacy.headers().firstValue("Content-Type").orElse("")).contains("application/atom+xml");
        assertThat(legacy.body()).contains("OPDS 2.0", "/opds/v2");
        assertThat(legacy.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(legacy.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(legacy.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(legacy.headers().firstValue("Content-Security-Policy").orElse(""))
                .contains("frame-ancestors 'none'", "base-uri 'none'");
    }

    @Test
    void rejectsPlainHttpWhenBindingBeyondLoopback() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();

        OpdsServerStatus status = server.start(new OpdsServerSettings("0.0.0.0", port, false, "", "", false));

        assertThat(status.running()).isFalse();
        assertThat(status.exposedBeyondLocalhost()).isTrue();
        assertThat(status.message()).contains("TLS/HTTPS");
        assertThat(server.status().running()).isFalse();
    }

    @Test
    void exposedServerUsesHttpsAndRequiresAuthenticationByDefault() throws Exception {
        System.setProperty(TLS_PASSWORD_PROPERTY, "changeit");
        MemorySettings tokenSettings = new MemorySettings();
        OpdsAccessTokenService tokens = new OpdsAccessTokenService(tokenSettings);
        var token = tokens.create("LAN client", Set.of(OpdsTokenScope.CATALOG_READ));
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null, tokens);
        int port = freePort();
        OpdsSecurityLimits limits = new OpdsSecurityLimits(16, 16, 8, 60, 120, false);
        OpdsServerSettings settings = new OpdsServerSettings("0.0.0.0", port, false, "", "", false,
                tlsSettings(""), limits);

        OpdsServerStatus status = server.start(settings);

        assertThat(status.running()).isTrue();
        assertThat(status.exposedBeyondLocalhost()).isTrue();
        assertThat(status.baseUrl()).isEqualTo("https://0.0.0.0:" + port + "/opds");
        assertThat(status.healthUrl()).isEqualTo("https://0.0.0.0:" + port + "/health");

        HttpClient client = httpsClient();
        var denied = get(client, "https://127.0.0.1:" + port + "/opds");
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("WWW-Authenticate"))
                .contains("Bearer realm=\"MyHomeLib OPDS\"");

        var root = get(client, "https://127.0.0.1:" + port + "/opds", bearer(token.token()));
        assertThat(root.statusCode()).isEqualTo(200);
        assertThat(root.body()).contains("MyHomeLib");
    }

    @Test
    void webLibraryWorksOverLanHttpsAndRequiresAuthentication() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();
        String hash = OpdsPasswordHash.hash("secret");
        OpdsServerSettings settings = new OpdsServerSettings("0.0.0.0", port, true, "reader", hash, false,
                tlsSettings("changeit"), OpdsSecurityLimits.defaults());
        assertThat(server.start(settings).running()).isTrue();

        HttpClient client = httpsClient();
        String uri = "https://127.0.0.1:" + port + "/web/";
        assertThat(get(client, uri).statusCode()).isEqualTo(401);
        var page = get(client, uri, basic("reader", "secret"));
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(page.body()).contains("MyHomeLib", "name=\"viewport\"");
    }

    @Test
    void healthIsPublicOnLoopbackButProtectedWhenExposed() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int localPort = freePort();
        server.start(new OpdsServerSettings("127.0.0.1", localPort, true, "reader", "secret", false));

        var localHealth = get(HttpClient.newHttpClient(), "http://127.0.0.1:" + localPort + "/health");
        assertThat(localHealth.statusCode()).isEqualTo(200);

        server.stop();
        int tlsPort = freePort();
        OpdsServerSettings exposed = new OpdsServerSettings("0.0.0.0", tlsPort, true, "reader", "secret", false,
                tlsSettings("changeit"), OpdsSecurityLimits.defaults());
        assertThat(server.start(exposed).running()).isTrue();

        HttpClient client = httpsClient();
        var denied = get(client, "https://127.0.0.1:" + tlsPort + "/health");
        assertThat(denied.statusCode()).isEqualTo(401);

        var allowed = get(client, "https://127.0.0.1:" + tlsPort + "/health", basic("reader", "secret"));
        assertThat(allowed.statusCode()).isEqualTo(200);
    }

    @Test
    void exposedHealthAndCatalogArePrivateWhenBasicAuthIsDisabled() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();
        OpdsServerSettings settings = new OpdsServerSettings("0.0.0.0", port, false, "", "", false,
                tlsSettings("changeit"), OpdsSecurityLimits.defaults());
        assertThat(server.start(settings).running()).isTrue();

        HttpClient client = httpsClient();
        assertThat(get(client, "https://127.0.0.1:" + port + "/opds").statusCode()).isEqualTo(401);
        assertThat(get(client, "https://127.0.0.1:" + port + "/health").statusCode()).isEqualTo(403);
    }

    @Test
    void catalogCanRequireBasicAuthWithHashedPassword() throws Exception {
        var catalog = new OpdsCatalogService(new FakeCatalog());
        server = new JdkOpdsServer(catalog, null);
        int port = freePort();
        String hash = OpdsPasswordHash.hash("secret");
        server.start(new OpdsServerSettings("127.0.0.1", port, true, "reader", hash, false));
        var client = HttpClient.newHttpClient();

        var denied = get(client, "http://127.0.0.1:" + port + "/opds");
        assertThat(denied.statusCode()).isEqualTo(401);

        var allowed = get(client, "http://127.0.0.1:" + port + "/opds", basic("reader", "secret"));
        assertThat(allowed.statusCode()).isEqualTo(200);

        var bad = get(client, "http://127.0.0.1:" + port + "/opds", basic("reader", "wrong"));
        assertThat(bad.statusCode()).isEqualTo(401);
    }


    @Test
    void bearerTokensAuthenticateRevokeImmediatelyAndEnforceScopes() throws Exception {
        MemorySettings tokenSettings = new MemorySettings();
        OpdsAccessTokenService tokens = new OpdsAccessTokenService(tokenSettings);
        var readToken = tokens.create("Browser", Set.of(OpdsTokenScope.CATALOG_READ));
        var catalog = new OpdsCatalogService(new FakeCatalog());
        server = new JdkOpdsServer(catalog, null, tokens);
        int port = freePort();
        String passwordHash = OpdsPasswordHash.hash("secret");
        server.start(new OpdsServerSettings("127.0.0.1", port, true, "reader", passwordHash, false));
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;

        assertThat(get(client, base + "/opds/v2").statusCode()).isEqualTo(401);
        assertThat(get(client, base + "/opds/v2", bearer(readToken.token())).statusCode()).isEqualTo(200);
        assertThat(tokens.list().getFirst().lastUsedAt()).isNotNull();

        var scopeDenied = get(client, base + "/opds/download/b1", bearer(readToken.token()));
        assertThat(scopeDenied.statusCode()).isEqualTo(403);
        assertThat(scopeDenied.headers().firstValue("WWW-Authenticate").orElse(""))
                .contains("insufficient_scope");

        assertThat(tokens.revoke(readToken.info().id())).isTrue();
        var revoked = get(client, base + "/opds/v2", bearer(readToken.token()));
        assertThat(revoked.statusCode()).isEqualTo(401);
    }

    @Test
    void webLibraryRequiresAuthenticationAndServesResponsiveCatalogueSearchDetailsAndContinue() throws Exception {
        MemorySettings tokenSettings = new MemorySettings();
        OpdsAccessTokenService tokens = new OpdsAccessTokenService(tokenSettings);
        var readToken = tokens.create("Browser", Set.of(OpdsTokenScope.CATALOG_READ));
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null, tokens);
        int port = freePort();
        server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false));
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;

        var denied = get(client, base + "/web/");
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("WWW-Authenticate").orElse("")).contains("Bearer");

        var library = get(client, base + "/web/?limit=1", bearer(readToken.token()));
        assertThat(library.statusCode()).isEqualTo(200);
        assertThat(library.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(library.body()).contains("name=\"viewport\"", "Книга &quot;Один&quot;", "Далі →", "/web/continue");

        var search = get(client, base + "/web/search?q=needle", bearer(readToken.token()));
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(search.body()).contains("Пошук: needle", "value=\"needle\"");

        var detail = get(client, base + "/web/books/b1", bearer(readToken.token()));
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("Анотація", "/web/download/b1", "/web/read/b1", "Читати");

        var reading = get(client, base + "/web/continue", bearer(readToken.token()));
        assertThat(reading.statusCode()).isEqualTo(200);
        assertThat(reading.body()).contains("Продовжити читання", "Книга &quot;Один&quot;");

        var downloadDenied = get(client, base + "/web/download/b1", bearer(readToken.token()));
        assertThat(downloadDenied.statusCode()).isEqualTo(403);
    }


    @Test
    void webReaderRendersEpubFb2UiResumesAndPersistsProgressThroughAuthenticatedPost() throws Exception {
        MemorySettings tokenSettings = new MemorySettings();
        OpdsAccessTokenService tokens = new OpdsAccessTokenService(tokenSettings);
        var readToken = tokens.create("Browser", Set.of(OpdsTokenScope.CATALOG_READ));
        java.util.concurrent.atomic.AtomicReference<ReadingProgressDto> saved = new java.util.concurrent.atomic.AtomicReference<>();
        WebReaderUseCase reader = new WebReaderUseCase() {
            @Override public Optional<WebReaderDocument> open(String bookId, int requestedChapter) {
                int selected = requestedChapter < 0 ? 1 : requestedChapter;
                return Optional.of(new WebReaderDocument(bookId, "Browser Book", "epub", true, "", java.util.List.of(
                        new WebReaderChapter(0, "c1", "One", 0, 10, "alpha beta"),
                        new WebReaderChapter(1, "c2", "Two", 11, 21, "gamma delta")
                ), selected, 1, 15, 71.4));
            }
            @Override public ReadingProgressDto saveProgress(String bookId, int chapterIndex, long absoluteOffset) {
                ReadingProgressDto dto = ReadingProgressDto.builder().bookId(bookId)
                        .anchorId(chapterIndex + ":" + absoluteOffset + ":0:0")
                        .chapterId("c" + (chapterIndex + 1)).percent(80).build();
                saved.set(dto);
                return dto;
            }
        };
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null, tokens, reader);
        int port = freePort();
        server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false));
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;

        assertThat(get(client, base + "/web/read/b1").statusCode()).isEqualTo(401);
        var page = get(client, base + "/web/read/b1", bearer(readToken.token()));
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Browser Book", "Зміст", "Two", "data-resume=\"15\"", "id=theme", "id=font");

        var rejected = post(client, base + "/web/read/b1/progress", bearer(readToken.token()),
                "{\"chapter\":1,\"offset\":18}", false);
        assertThat(rejected.statusCode()).isEqualTo(403);

        var progress = post(client, base + "/web/read/b1/progress", bearer(readToken.token()),
                "{\"chapter\":1,\"offset\":18}", true);
        assertThat(progress.statusCode()).isEqualTo(200);
        assertThat(progress.body()).contains("80.0000");
        assertThat(saved.get().getAnchorId()).isEqualTo("1:18:0:0");
    }

    @Test
    void continueReadingWebShelfShowsSyncedProgressDeviceAndTime() throws Exception {
        MemorySettings tokenSettings = new MemorySettings();
        OpdsAccessTokenService tokens = new OpdsAccessTokenService(tokenSettings);
        var readToken = tokens.create("Browser", Set.of(OpdsTokenScope.CATALOG_READ));
        ContinueReadingService shelf = new ContinueReadingService(limit -> java.util.List.of(
                new ContinueReadingItemDto("b1", "Synced Book", "Author", 64.5, "Chapter 4",
                        LocalDateTime.of(2026, 9, 12, 18, 10), "phone-1")));
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null, tokens, null, shelf);
        int port = freePort();
        server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false));

        var response = get(HttpClient.newHttpClient(), "http://127.0.0.1:" + port + "/web/continue", bearer(readToken.token()));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Synced Book", "64.5%", "phone-1", "12.09.2026 18:10", "/web/read/b1");
    }

    @Test
    void repeatedBadCredentialsTriggerPerClientThrottling() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();
        OpdsSecurityLimits limits = new OpdsSecurityLimits(16, 16, 3, 60, 120, true);
        OpdsServerSettings settings = new OpdsServerSettings("127.0.0.1", port, true, "reader", "secret", false,
                OpdsTlsSettings.disabled(), limits);
        server.start(settings);
        HttpClient client = HttpClient.newHttpClient();
        String uri = "http://127.0.0.1:" + port + "/opds";

        assertThat(get(client, uri, basic("reader", "bad-1")).statusCode()).isEqualTo(401);
        assertThat(get(client, uri, basic("reader", "bad-2")).statusCode()).isEqualTo(401);
        HttpResponse<String> throttled = get(client, uri, basic("reader", "bad-3"));
        assertThat(throttled.statusCode()).isEqualTo(429);
        assertThat(throttled.headers().firstValue("Retry-After")).isPresent();

        // A correct password cannot bypass an active block from the same client IP.
        assertThat(get(client, uri, basic("reader", "secret")).statusCode()).isEqualTo(429);
    }

    @Test
    void maxConcurrentRequestsAppliesBackPressureWithoutBreakingNormalRequest() throws Exception {
        BlockingCatalog blocking = new BlockingCatalog();
        server = new JdkOpdsServer(new OpdsCatalogService(blocking), null);
        int port = freePort();
        OpdsSecurityLimits limits = new OpdsSecurityLimits(1, 4, 8, 60, 120, true);
        server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false,
                OpdsTlsSettings.disabled(), limits));
        String uri = "http://127.0.0.1:" + port + "/opds/authors";

        HttpClient firstClient = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> first = firstClient.sendAsync(
                HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(blocking.entered.await(3, TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> overloaded = get(HttpClient.newHttpClient(), uri);
        assertThat(overloaded.statusCode()).isEqualTo(503);
        assertThat(overloaded.headers().firstValue("Retry-After")).contains("1");

        blocking.release.countDown();
        assertThat(first.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    }

    @Test
    void failedTlsStartCleansResourcesAndAllowsImmediateRestart() throws Exception {
        server = new JdkOpdsServer(new OpdsCatalogService(new FakeCatalog()), null);
        int port = freePort();
        OpdsTlsSettings missing = new OpdsTlsSettings(true, "/definitely/missing/opds.p12", "PKCS12", "changeit");
        OpdsServerSettings broken = new OpdsServerSettings("0.0.0.0", port, false, "", "", false,
                missing, OpdsSecurityLimits.defaults());

        OpdsServerStatus failed = server.start(broken);
        assertThat(failed.running()).isFalse();
        assertThat(failed.message()).contains("does not exist");

        OpdsServerStatus restarted = server.start(new OpdsServerSettings("127.0.0.1", port, false, "", "", false));
        assertThat(restarted.running()).isTrue();
        assertThat(get(HttpClient.newHttpClient(), "http://127.0.0.1:" + port + "/opds").statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, String uri, String authorization) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).header("Authorization", authorization).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }


    private static HttpResponse<String> post(HttpClient client, String uri, String authorization, String json, boolean marker) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json");
        if (marker) builder.header("X-MyHomeLib-Request", "1");
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String username, String password) {
        String token = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static OpdsTlsSettings tlsSettings(String password) throws Exception {
        Path path = Path.of(JdkOpdsServerTest.class.getResource("/tls/opds-test.p12").toURI());
        return new OpdsTlsSettings(true, path.toString(), "PKCS12", password);
    }

    private static HttpClient httpsClient() throws Exception {
        TrustManager[] trustAll = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trustAll, new SecureRandom());
        return HttpClient.newBuilder().sslContext(ssl).build();
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static class FakeCatalog implements OpdsCatalogQueryPort {
        @Override public OpdsPage<OpdsFacetDto> authors(int offset, int limit) {
            var all = java.util.List.of(new OpdsFacetDto("a1", "Автор Один", 2), new OpdsFacetDto("a2", "Автор Два", 1));
            int from = Math.min(offset, all.size());
            int to = Math.min(from + limit, all.size());
            return new OpdsPage<>(all.subList(from, to), all.size(), offset, limit);
        }
        @Override public OpdsPage<OpdsFacetDto> series(int offset, int limit) { return new OpdsPage<>(java.util.List.of(), 0, offset, limit); }
        @Override public OpdsPage<OpdsFacetDto> genres(int offset, int limit) { return new OpdsPage<>(java.util.List.of(), 0, offset, limit); }
        @Override public OpdsPage<OpdsBookDto> books(OpdsBookQuery query) {
            var all = java.util.List.of(book("b1", "Книга \"Один\""), book("b2", "Друга книга"));
            int from = Math.min(query.offset(), all.size());
            int to = Math.min(from + query.limit(), all.size());
            return new OpdsPage<>(all.subList(from, to), all.size(), query.offset(), query.limit());
        }
        @Override public Optional<OpdsBookDto> book(String bookId) {
            return "b1".equals(bookId) ? Optional.of(book("b1", "Книга \"Один\"")) : Optional.empty();
        }
        @Override public Optional<OpdsFacetDto> currentCollection() {
            return Optional.of(new OpdsFacetDto("c1", "Основна колекція", 2));
        }
        @Override public OpdsPage<OpdsFacetDto> groups(int offset, int limit) {
            var all = java.util.List.of(new OpdsFacetDto("1", "Обране", 1), new OpdsFacetDto("2", "До читання", 1));
            int from = Math.min(offset, all.size());
            int to = Math.min(from + limit, all.size());
            return new OpdsPage<>(all.subList(from, to), all.size(), offset, limit);
        }
        @Override public OpdsPage<OpdsBookDto> groupBooks(String groupId, int offset, int limit) {
            return pageOf(book("b1", "Книга \"Один\""), offset, limit);
        }
        @Override public OpdsPage<OpdsBookDto> favorites(int offset, int limit) {
            return pageOf(book("b1", "Книга \"Один\""), offset, limit);
        }
        @Override public OpdsPage<OpdsBookDto> continueReading(int offset, int limit) {
            return pageOf(book("b1", "Книга \"Один\""), offset, limit);
        }
        private static OpdsPage<OpdsBookDto> pageOf(OpdsBookDto book, int offset, int limit) {
            var all = java.util.List.of(book);
            int from = Math.min(offset, all.size());
            int to = Math.min(from + limit, all.size());
            return new OpdsPage<>(all.subList(from, to), all.size(), offset, limit);
        }
        private static OpdsBookDto book(String id, String title) {
            return new OpdsBookDto(id, title, "Автор", "Серія", "uk", 2026, "Анотація",
                    "fb2", true, id + ".fb2", "");
        }
    }

    private static final class MemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new LinkedHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { if (value == null) values.remove(key); else values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }

    private static final class BlockingCatalog extends FakeCatalog {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public OpdsPage<OpdsFacetDto> authors(int offset, int limit) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return super.authors(offset, limit);
        }
    }
}
