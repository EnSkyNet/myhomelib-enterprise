package com.myhomelibcorp.opds;

import com.myhomelibcorp.shared.text.JsonStrings;
import com.myhomelibcorp.application.opds.*;
import com.myhomelibcorp.application.kobo.*;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.webreader.WebReaderUseCase;
import com.myhomelibcorp.application.webreader.WebProgressUpdate;
import com.myhomelibcorp.application.usecase.reading.ContinueReadingService;
import com.myhomelibcorp.web.WebLibraryRenderer;
import com.myhomelibcorp.web.WebPwaAssets;
import com.myhomelibcorp.web.OfflineBookPackageRenderer;
import com.myhomelibcorp.web.WebReaderRenderer;
import com.myhomelibcorp.web.WebTextCatalog;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Small OPDS 1.x sidecar based only on the JDK HTTP server. It is deliberately
 * outside JavaFX/controllers and talks only to application-level read services.
 */
@Component
@Slf4j
public class JdkOpdsServer implements com.myhomelibcorp.application.opds.OpdsServerControl, DisposableBean {
    private static final String ATOM = "application/atom+xml;profile=opds-catalog;charset=utf-8";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_TLS_KEYSTORE_BYTES = 32 * 1024 * 1024;

    private final OpdsCatalogService catalog;
    private final OpdsDownloadService downloads;
    private final OpdsAccessTokenService accessTokens;
    private final WebTextCatalog webText = new WebTextCatalog();
    private final Opds2JsonRenderer opds2 = new Opds2JsonRenderer(webText);
    private final WebLibraryRenderer web = new WebLibraryRenderer(webText);
    private final WebReaderRenderer webReaderRenderer = new WebReaderRenderer(webText);
    private final WebPwaAssets pwaAssets = new WebPwaAssets(webText);
    private final OfflineBookPackageRenderer offlineBookPackages = new OfflineBookPackageRenderer();
    private final WebReaderUseCase webReader;
    private final ContinueReadingService continueReading;
    private final KoboSyncService koboSync;
    private KoboContentService koboDownloads;
    private final KoboProtocolRenderer koboProtocol = new KoboProtocolRenderer();

    public JdkOpdsServer(OpdsCatalogService catalog, OpdsDownloadService downloads) {
        this(catalog, downloads, null, null, null, null);
    }

    public JdkOpdsServer(OpdsCatalogService catalog, OpdsDownloadService downloads, OpdsAccessTokenService accessTokens) {
        this(catalog, downloads, accessTokens, null, null, null);
    }

    public JdkOpdsServer(OpdsCatalogService catalog, OpdsDownloadService downloads,
                         OpdsAccessTokenService accessTokens, WebReaderUseCase webReader) {
        this(catalog, downloads, accessTokens, webReader, null, null);
    }

    public JdkOpdsServer(OpdsCatalogService catalog, OpdsDownloadService downloads,
                         OpdsAccessTokenService accessTokens, WebReaderUseCase webReader,
                         ContinueReadingService continueReading) {
        this(catalog, downloads, accessTokens, webReader, continueReading, null);
    }

    @Autowired
    public JdkOpdsServer(OpdsCatalogService catalog, OpdsDownloadService downloads,
                         OpdsAccessTokenService accessTokens, WebReaderUseCase webReader,
                         ContinueReadingService continueReading, KoboSyncService koboSync) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.downloads = downloads;
        this.accessTokens = accessTokens;
        this.webReader = webReader;
        this.continueReading = continueReading;
        this.koboSync = koboSync;
    }
    @Autowired
    void setKoboDownloadService(KoboContentService service) {
        this.koboDownloads = service;
    }

    private final AtomicReference<HttpServer> server = new AtomicReference<>();
    private final AtomicReference<ExecutorService> executor = new AtomicReference<>();
    private final AtomicReference<OpdsServerStatus> status = new AtomicReference<>(OpdsServerStatus.stopped());
    private final AtomicReference<OpdsRequestLimiter> requestLimiter = new AtomicReference<>(new OpdsRequestLimiter(OpdsSecurityLimits.defaults()));
    private volatile OpdsServerSettings activeSettings = OpdsServerSettings.defaults();
    private volatile boolean activeExposedBeyondLoopback;

    @Override
    public synchronized OpdsServerStatus start(OpdsServerSettings requested) {
        OpdsServerSettings settings = requested == null ? OpdsServerSettings.defaults() : requested;
        stop();

        HttpServer created = null;
        ExecutorService pool = null;
        try {
            InetAddress address = InetAddress.getByName(settings.bindAddress());
            boolean exposed = !address.isLoopbackAddress();
            if (exposed && !settings.tls().enabled()) {
                throw new IllegalArgumentException("LAN OPDS requires TLS/HTTPS; plaintext HTTP is allowed only on loopback");
            }

            SSLContext sslContext = settings.tls().enabled() ? createSslContext(settings.tls()) : null;
            InetSocketAddress socketAddress = new InetSocketAddress(address, settings.port());
            created = createHttpServer(socketAddress, settings.limits().listenBacklog(), sslContext);
            created.createContext("/", this::handle);
            pool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("opds-http-", 0).factory());
            created.setExecutor(pool);

            activeSettings = settings;
            activeExposedBeyondLoopback = exposed;
            requestLimiter.set(new OpdsRequestLimiter(settings.limits()));
            created.start();

            server.set(created);
            executor.set(pool);
            int actualPort = created.getAddress().getPort();
            String scheme = settings.tls().enabled() ? "https" : "http";
            String host = displayHost(settings.bindAddress());
            OpdsServerStatus result = new OpdsServerStatus(true, settings.bindAddress(), actualPort,
                    scheme + "://" + host + ":" + actualPort + "/opds", exposed,
                    "ui.opds.status.running", "");
            status.set(result);
            log.info("OPDS started at {}", result.baseUrl());
            return result;
        } catch (Exception e) {
            cleanupLocalResources(created, pool);
            activeSettings = OpdsServerSettings.defaults();
            activeExposedBeyondLoopback = false;
            requestLimiter.set(new OpdsRequestLimiter(OpdsSecurityLimits.defaults()));
            OpdsServerStatus failed = new OpdsServerStatus(false, settings.bindAddress(), settings.port(), "",
                    !isLoopback(settings.bindAddress()), "ui.opds.status.start_failed", safeMessage(e));
            status.set(failed);
            log.warn("Cannot start OPDS: {}", safeMessage(e));
            return failed;
        }
    }

    @Override
    public synchronized void stop() {
        HttpServer current = server.getAndSet(null);
        if (current != null) {
            current.stop(1);
            log.info("OPDS stopped");
        }
        ExecutorService pool = executor.getAndSet(null);
        if (pool != null) pool.shutdownNow();
        activeSettings = OpdsServerSettings.defaults();
        activeExposedBeyondLoopback = false;
        requestLimiter.set(new OpdsRequestLimiter(OpdsSecurityLimits.defaults()));
        status.set(OpdsServerStatus.stopped());
    }

    @Override
    public OpdsServerStatus status() { return status.get(); }

    @Override
    public void destroy() {
        stop();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String language = requestLanguage(exchange);
        exchange.getResponseHeaders().set("Content-Language", language);
        OpdsRequestLimiter limiter = requestLimiter.get();
        OpdsRequestLimiter.RequestPermit permit = limiter.tryAcquireRequest();
        if (permit == null) {
            try (exchange) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                exchange.getResponseHeaders().set("Connection", "close");
                sendText(exchange, 503, t(language, "web.error.server_busy"));
            }
            return;
        }

        try (exchange; permit) {
            try {
                String path = normalizePath(exchange.getRequestURI().getPath());
                String method = exchange.getRequestMethod() == null ? "" : exchange.getRequestMethod().toUpperCase(Locale.ROOT);
                if (path.startsWith("/kobo/")) {
                    handleKobo(exchange, path, method);
                    return;
                }
                boolean webProgressPost = "POST".equals(method) && path.startsWith("/web/read/") && path.endsWith("/progress");
                if (!"GET".equals(method) && !webProgressPost) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    exchange.getResponseHeaders().set("Connection", "close");
                    sendText(exchange, 405, t(language, "web.error.method_not_allowed"));
                    return;
                }
                if ("GET".equals(method)) exchange.getRequestBody().close();
                if ("/health".equals(path)) {
                    if (!healthAuthorized(exchange)) return;
                    sendJson(exchange, 200, "{\"status\":\"UP\",\"opds\":\"running\"}");
                    return;
                }
                if ("GET".equals(method) && isPublicPwaAsset(path)) {
                    sendPublicPwaAsset(exchange, path, language);
                    return;
                }
                if (path.startsWith("/web")) {
                    OpdsTokenScope requiredScope = path.startsWith("/web/download/")
                            ? OpdsTokenScope.DOWNLOAD : OpdsTokenScope.CATALOG_READ;
                    if (!webAuthorized(exchange, requiredScope)) return;
                    handleWeb(exchange, path, query(exchange.getRequestURI()), method, language);
                    return;
                }
                if (!path.startsWith("/opds")) {
                    sendText(exchange, 404, t(language, "web.error.not_found"));
                    return;
                }
                OpdsTokenScope requiredScope = path.startsWith("/opds/download/")
                        ? OpdsTokenScope.DOWNLOAD : OpdsTokenScope.CATALOG_READ;
                if (!authorized(exchange, requiredScope)) return;

                Map<String, String> query = query(exchange.getRequestURI());
                if ("/opds/v2".equals(path) || "/opds/v2/".equals(path)) {
                    sendOpds2(exchange, 200, opds2.root(language));
                } else if ("/opds/v2/library".equals(path)) {
                    sendOpds2(exchange, 200, opds2.publications(language, t(language, "web.opds.library"), "/opds/v2/library",
                            catalog.books(OpdsBookQuery.all(offset(query), limit(query)))));
                } else if ("/opds/v2/collections".equals(path)) {
                    sendOpds2(exchange, 200, opds2.collections(language, catalog.currentCollection()));
                } else if (path.startsWith("/opds/v2/collections/")) {
                    String id = decode(segmentAfter(path, "/opds/v2/collections/"));
                    var active = catalog.currentCollection();
                    if (active.isEmpty() || !Objects.equals(active.get().id(), id)) {
                        sendText(exchange, 404, t(language, "web.error.collection_not_found"));
                    } else {
                        String self = "/opds/v2/collections/" + encode(id);
                        sendOpds2(exchange, 200, opds2.publications(language, active.get().label(), self,
                                catalog.books(OpdsBookQuery.all(offset(query), limit(query)))));
                    }
                } else if ("/opds/v2/groups".equals(path)) {
                    sendOpds2(exchange, 200, opds2.groups(language, catalog.groups(offset(query), limit(query))));
                } else if (path.startsWith("/opds/v2/groups/")) {
                    String id = decode(segmentAfter(path, "/opds/v2/groups/"));
                    String self = "/opds/v2/groups/" + encode(id);
                    sendOpds2(exchange, 200, opds2.publications(language, t(language, "web.opds.group"), self,
                            catalog.groupBooks(id, offset(query), limit(query))));
                } else if ("/opds/v2/favorites".equals(path)) {
                    sendOpds2(exchange, 200, opds2.publications(language, t(language, "web.opds.favorites"), "/opds/v2/favorites",
                            catalog.favorites(offset(query), limit(query))));
                } else if ("/opds/v2/continue".equals(path)) {
                    sendOpds2(exchange, 200, opds2.publications(language, t(language, "web.opds.continue"), "/opds/v2/continue",
                            catalog.continueReading(offset(query), limit(query))));
                } else if ("/opds/v2/search".equals(path)) {
                    String search = query.getOrDefault("q", "");
                    String self = "/opds/v2/search?q=" + encode(search);
                    sendOpds2(exchange, 200, opds2.publications(language, t(language, "web.opds.search_title", search), self,
                            catalog.books(new OpdsBookQuery("", "", "", search, offset(query), limit(query)))));
                } else if (path.startsWith("/opds/v2/books/")) {
                    String id = decode(segmentAfter(path, "/opds/v2/books/"));
                    var book = catalog.book(id);
                    if (book.isEmpty()) sendText(exchange, 404, t(language, "web.error.book_not_found"));
                    else sendOpds2(exchange, 200, opds2.publication(language, book.get()));
                } else if ("/opds".equals(path) || "/opds/".equals(path)) {
                    sendXml(exchange, 200, rootFeed(language));
                } else if ("/opds/authors".equals(path)) {
                    sendXml(exchange, 200, facetFeed(language, t(language, "web.opds.authors"), "/opds/authors", catalog.authors(offset(query), limit(query))));
                } else if (path.startsWith("/opds/authors/")) {
                    String id = decode(segmentAfter(path, "/opds/authors/"));
                    sendXml(exchange, 200, booksFeed(language, t(language, "web.opds.books_author"), "/opds/authors/" + encode(id),
                            catalog.books(new OpdsBookQuery(id, "", "", "", offset(query), limit(query)))));
                } else if ("/opds/series".equals(path)) {
                    sendXml(exchange, 200, facetFeed(language, t(language, "web.opds.series"), "/opds/series", catalog.series(offset(query), limit(query))));
                } else if (path.startsWith("/opds/series/")) {
                    String id = decode(segmentAfter(path, "/opds/series/"));
                    sendXml(exchange, 200, booksFeed(language, t(language, "web.opds.books_series"), "/opds/series/" + encode(id),
                            catalog.books(new OpdsBookQuery("", id, "", "", offset(query), limit(query)))));
                } else if ("/opds/genres".equals(path)) {
                    sendXml(exchange, 200, facetFeed(language, t(language, "web.opds.genres"), "/opds/genres", catalog.genres(offset(query), limit(query))));
                } else if (path.startsWith("/opds/genres/")) {
                    String id = decode(segmentAfter(path, "/opds/genres/"));
                    sendXml(exchange, 200, booksFeed(language, t(language, "web.opds.books_genre"), "/opds/genres/" + encode(id),
                            catalog.books(new OpdsBookQuery("", "", id, "", offset(query), limit(query)))));
                } else if ("/opds/search".equals(path)) {
                    String search = query.getOrDefault("q", "");
                    sendXml(exchange, 200, booksFeed(language, t(language, "web.opds.search_title", search), "/opds/search?q=" + encode(search),
                            catalog.books(new OpdsBookQuery("", "", "", search, offset(query), limit(query)))));
                } else if (path.startsWith("/opds/books/")) {
                    String id = decode(segmentAfter(path, "/opds/books/"));
                    var book = catalog.book(id);
                    if (book.isEmpty()) sendText(exchange, 404, t(language, "web.error.book_not_found"));
                    else sendXml(exchange, 200, bookEntry(language, book.get()));
                } else if (path.startsWith("/opds/download/")) {
                    download(exchange, decode(segmentAfter(path, "/opds/download/")));
                } else {
                    sendText(exchange, 404, t(language, "web.error.not_found"));
                }
            } catch (IllegalStateException e) {
                sendTextSafe(exchange, 503, t(language, "web.error.no_active_collection"));
            } catch (Exception e) {
                log.warn("OPDS request failed: {}", safeRequestPath(exchange.getRequestURI()), e);
                sendTextSafe(exchange, 500, t(language, "web.error.internal"));
            }
        }
    }

    private void handleKobo(HttpExchange exchange, String path, String method) throws IOException {
        if (koboSync == null) {
            exchange.getRequestBody().close();
            sendJson(exchange, 503, "{\"error\":\"kobo_sync_unavailable\"}");
            return;
        }
        String tail = path.substring("/kobo/".length());
        int slash = tail.indexOf('/');
        String rawToken = decode(slash < 0 ? tail : tail.substring(0, slash));
        String endpoint = slash < 0 ? "" : tail.substring(slash);
        if (!koboAuthorized(exchange, rawToken)) return;

        String base = koboBaseUrl(exchange, rawToken);
        if ((endpoint.isEmpty() || "/".equals(endpoint) || "/v1/initialization".equals(endpoint)) && "GET".equals(method)) {
            exchange.getRequestBody().close();
            sendJson(exchange, 200, koboProtocol.initialization(base));
            return;
        }
        if (("/v1/auth/device".equals(endpoint) || "/v1/auth/refresh".equals(endpoint)) && "POST".equals(method)) {
            readBounded(exchange.getRequestBody(), 64 * 1024);
            sendJson(exchange, 200, koboProtocol.deviceAuth(koboSync.status().deviceId()));
            return;
        }
        if ("/v1/library/sync".equals(endpoint) && "GET".equals(method)) {
            exchange.getRequestBody().close();
            KoboSyncCursor cursor = koboProtocol.parseToken(exchange.getRequestHeaders().getFirst("x-kobo-synctoken"));
            KoboSyncBatch batch = koboSync.synchronize(cursor, 100);
            exchange.getResponseHeaders().set("x-kobo-synctoken", koboProtocol.token(batch.nextCursor()));
            if (batch.hasMore()) exchange.getResponseHeaders().set("x-kobo-sync", "continue");
            sendJson(exchange, 200, koboProtocol.sync(batch, base));
            return;
        }
        if (endpoint.startsWith("/v1/library/")) {
            handleKoboLibrary(exchange, endpoint, method, base);
            return;
        }
        if (endpoint.startsWith("/download/") && "GET".equals(method)) {
            exchange.getRequestBody().close();
            String rest = endpoint.substring("/download/".length());
            int next = rest.indexOf('/');
            String bookId = decode(next < 0 ? rest : rest.substring(0, next));
            var projection = koboSync.book(bookId);
            if (projection.isEmpty()) {
                sendJson(exchange, 404, "{\"error\":\"book_not_found\"}");
            } else if (koboDownloads == null) {
                sendJson(exchange, 503, "{\"error\":\"content_service_unavailable\"}");
            } else {
                downloadKobo(exchange, projection.get());
            }
            return;
        }
        exchange.getRequestBody().close();
        exchange.getResponseHeaders().set("Allow", "GET, POST, PUT");
        sendJson(exchange, 404, "{\"error\":\"not_found\"}");
    }

    private void handleKoboLibrary(HttpExchange exchange, String endpoint, String method, String base) throws IOException {
        String rest = endpoint.substring("/v1/library/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            exchange.getRequestBody().close();
            sendJson(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        String bookId = decode(rest.substring(0, slash));
        String action = rest.substring(slash);
        if ("/metadata".equals(action) && "GET".equals(method)) {
            exchange.getRequestBody().close();
            var book = koboSync.book(bookId);
            if (book.isEmpty()) sendJson(exchange, 404, "{\"error\":\"book_not_found\"}");
            else sendJson(exchange, 200, koboProtocol.metadata(book.get(), base));
            return;
        }
        if ("/state".equals(action) && "GET".equals(method)) {
            exchange.getRequestBody().close();
            var state = koboSync.progress(bookId);
            if (state.isEmpty()) sendJson(exchange, 200, "{\"ReadingState\":null}");
            else sendJson(exchange, 200, koboProtocol.state(state.get()));
            return;
        }
        if ("/state".equals(action) && "PUT".equals(method)) {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                exchange.getRequestBody().close();
                sendJson(exchange, 415, "{\"error\":\"expected_json\"}");
                return;
            }
            String json = new String(readBounded(exchange.getRequestBody(), 32 * 1024), StandardCharsets.UTF_8);
            Double rawProgress = jsonDouble(json, "ProgressPercent");
            double percent = rawProgress == null ? 0.0 : (rawProgress <= 1.00000001 ? rawProgress * 100.0 : rawProgress);
            String status = nonBlank(jsonString(json, "Status"), percent >= 99.999 ? "Finished" : percent > 0 ? "Reading" : "ReadyToRead");
            String location = nonBlank(jsonString(json, "Value"), "");
            Instant modified = parseInstantOrNow(jsonString(json, "LastModified"));
            Long bodyVersion = jsonLong(json, "MyHomeLibVersion");
            Long headerVersion = parseNonNegativeLong(exchange.getRequestHeaders().getFirst("X-MyHomeLib-Progress-Version"));
            long baseVersion = bodyVersion != null && bodyVersion >= 0 ? bodyVersion
                    : headerVersion != null ? headerVersion
                    : koboSync.progress(bookId).map(KoboProgressState::version).orElse(0L);
            try {
                KoboProgressState accepted = koboSync.updateProgress(new KoboProgressUpdate(
                        bookId, percent, status, location, modified, baseVersion));
                sendJson(exchange, 200, koboProtocol.state(accepted));
            } catch (IllegalArgumentException invalid) {
                sendJson(exchange, 400, "{\"error\":\"invalid_progress\"}");
            }
            return;
        }
        exchange.getRequestBody().close();
        exchange.getResponseHeaders().set("Allow", "GET, PUT");
        sendJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
    }

    private boolean koboAuthorized(HttpExchange exchange, String token) throws IOException {
        String client = clientKey(exchange);
        OpdsRequestLimiter limiter = requestLimiter.get();
        OpdsRequestLimiter.AuthThrottle throttle = limiter.beforeAuthentication(client);
        if (throttle.blocked()) {
            sendThrottled(exchange, throttle.retryAfterSeconds());
            return false;
        }
        if (token != null && !token.isBlank() && koboSync.authenticate(token)) {
            limiter.authenticationSucceeded(client);
            return true;
        }
        OpdsRequestLimiter.AuthThrottle after = limiter.authenticationFailed(client);
        exchange.getResponseHeaders().set("Connection", "close");
        if (after.blocked()) sendThrottled(exchange, after.retryAfterSeconds());
        else sendJson(exchange, 401, "{\"error\":\"invalid_device_credential\"}");
        return false;
    }

    private String koboBaseUrl(HttpExchange exchange, String token) {
        String scheme = activeSettings.tls().enabled() ? "https" : "http";
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !host.matches("[A-Za-z0-9.\\-\\[\\]:]+")) {
            InetSocketAddress local = exchange.getLocalAddress();
            host = local.getHostString() + ":" + local.getPort();
        }
        return scheme + "://" + host + "/kobo/" + encode(token);
    }

    private static URI safeRequestPath(URI uri) {
        if (uri == null) return URI.create("/");
        String path = normalizePath(uri.getPath());
        if (path.startsWith("/kobo/")) {
            String tail = path.substring("/kobo/".length());
            int slash = tail.indexOf('/');
            path = "/kobo/<redacted>" + (slash < 0 ? "" : tail.substring(slash));
        }
        try { return new URI(null, null, path, null); }
        catch (Exception ignored) { return URI.create("/"); }
    }

    private static Double jsonDouble(String json, String field) {
        if (json == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\\\"" + java.util.regex.Pattern.quote(field) + "\\\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][+-]?\\d+)?)")
                .matcher(json);
        if (!matcher.find()) return null;
        try { return Double.parseDouble(matcher.group(1)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String jsonString(String json, String field) {
        if (json == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\\\"" + java.util.regex.Pattern.quote(field) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
                .matcher(json);
        if (!matcher.find()) return null;
        return unescapeJsonString(matcher.group(1));
    }

    private static String unescapeJsonString(String value) {
        if (value == null || value.indexOf('\\') < 0) return value;
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!escaped) {
                if (c == '\\') escaped = true; else out.append(c);
            } else {
                escaped = false;
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> out.append(c);
                }
            }
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    private static Instant parseInstantOrNow(String value) {
        if (value != null && !value.isBlank()) {
            try { return Instant.parse(value); } catch (RuntimeException ignored) { }
        }
        return Instant.now();
    }

    private static Long parseNonNegativeLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (RuntimeException ignored) { return null; }
    }

    private void handleWeb(HttpExchange exchange, String path, Map<String, String> query, String method, String language) throws IOException {
        if ("POST".equals(method) && path.startsWith("/web/read/") && path.endsWith("/progress")) {
            saveWebReaderProgress(exchange, path, language);
            return;
        }
        if ("/web".equals(path) || "/web/".equals(path)) {
            sendHtml(exchange, 200, web.books(language, t(language, "web.library.title"), "/web/", "",
                    catalog.books(OpdsBookQuery.all(offset(query), limit(query)))));
        } else if ("/web/search".equals(path)) {
            String search = query.getOrDefault("q", "");
            sendHtml(exchange, 200, web.books(language, t(language, "web.search.title", search), "/web/search?q=" + encode(search), search,
                    catalog.books(new OpdsBookQuery("", "", "", search, offset(query), limit(query)))));
        } else if ("/web/continue".equals(path)) {
            if (continueReading != null) {
                sendHtml(exchange, 200, web.continueReading(language, continueReading.recent(limit(query))));
            } else {
                sendHtml(exchange, 200, web.books(language, t(language, "web.continue.title"), "/web/continue", "",
                        catalog.continueReading(offset(query), limit(query))));
            }
        } else if (path.startsWith("/web/offline/books/") && path.endsWith("/manifest.json")) {
            if (webReader == null) {
                sendText(exchange, 503, t(language, "web.reader.unavailable"));
                return;
            }
            String encoded = path.substring("/web/offline/books/".length(), path.length() - "/manifest.json".length());
            String id = decode(encoded);
            var document = webReader.open(id, -1);
            if (document.isEmpty()) {
                sendJson(exchange, 404, "{\"error\":\"book_not_found\"}");
            } else if (!document.get().supported() || document.get().chapters().isEmpty()) {
                sendJson(exchange, 422, "{\"error\":\"unsupported_format\"}");
            } else {
                sendJson(exchange, 200, offlineBookPackages.manifest(document.get()));
            }
        } else if (path.startsWith("/web/read/")) {
            if (webReader == null) {
                sendText(exchange, 503, t(language, "web.reader.unavailable"));
                return;
            }
            String id = decode(segmentAfter(path, "/web/read/"));
            int chapter = integer(query.get("chapter"), -1, -1, 1_000_000);
            var document = webReader.open(id, chapter);
            if (document.isEmpty()) sendHtml(exchange, 404, webReaderRenderer.notFound(language));
            else sendHtml(exchange, 200, webReaderRenderer.render(language, document.get()));
        } else if (path.startsWith("/web/books/")) {
            String id = decode(segmentAfter(path, "/web/books/"));
            var book = catalog.book(id);
            if (book.isEmpty()) sendHtml(exchange, 404, web.notFound(language));
            else sendHtml(exchange, 200, web.book(language, book.get()));
        } else if (path.startsWith("/web/download/")) {
            download(exchange, decode(segmentAfter(path, "/web/download/")));
        } else {
            sendText(exchange, 404, t(language, "web.error.not_found"));
        }
    }

    private void saveWebReaderProgress(HttpExchange exchange, String path, String language) throws IOException {
        if (webReader == null) {
            sendText(exchange, 503, t(language, "web.reader.unavailable"));
            return;
        }
        if (!"1".equals(exchange.getRequestHeaders().getFirst("X-MyHomeLib-Request"))) {
            sendText(exchange, 403, t(language, "web.error.same_origin_marker"));
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            sendText(exchange, 415, t(language, "web.error.expected_json"));
            return;
        }
        String idPart = path.substring("/web/read/".length(), path.length() - "/progress".length());
        String id = decode(idPart);
        byte[] body = readBounded(exchange.getRequestBody(), 2048);
        String json = new String(body, StandardCharsets.UTF_8);
        Long chapter = jsonLong(json, "chapter");
        Long offset = jsonLong(json, "offset");
        Long clientTimestamp = jsonLong(json, "clientTimestamp");
        Long clientSequence = jsonLong(json, "clientSequence");
        if (chapter == null || offset == null || chapter < 0 || chapter > 1_000_000 || offset < 0
                || (clientTimestamp != null && clientTimestamp < 0)
                || (clientSequence != null && clientSequence < 0)) {
            sendText(exchange, 400, t(language, "web.error.invalid_progress"));
            return;
        }
        long timestamp = clientTimestamp == null ? System.currentTimeMillis() : clientTimestamp;
        long sequence = clientSequence == null ? 0L : clientSequence;
        final java.time.Instant clientInstant;
        try {
            clientInstant = java.time.Instant.ofEpochMilli(timestamp);
        } catch (RuntimeException invalidTimestamp) {
            sendText(exchange, 400, t(language, "web.error.invalid_progress"));
            return;
        }
        var result = webReader.saveProgress(id, new WebProgressUpdate(chapter.intValue(), offset, clientInstant, sequence));
        var canonical = result.canonical();
        String updatedAt = canonical.getUpdatedAt() == null ? "" : canonical.getUpdatedAt().toString();
        sendJson(exchange, 200, "{\"accepted\":" + result.accepted()
                + ",\"percent\":" + String.format(Locale.ROOT, "%.4f", canonical.getPercent())
                + ",\"anchorId\":\"" + JsonStrings.escape(canonical.getAnchorId()) + "\""
                + ",\"updatedAt\":\"" + JsonStrings.escape(updatedAt) + "\""
                + ",\"clientSequence\":" + sequence + "}");
    }

    private static boolean isPublicPwaAsset(String path) {
        return WebPwaAssets.MANIFEST_PATH.equals(path)
                || WebPwaAssets.SERVICE_WORKER_PATH.equals(path)
                || WebPwaAssets.OFFLINE_PATH.equals(path)
                || WebPwaAssets.CLIENT_JS_PATH.equals(path)
                || WebPwaAssets.CSS_PATH.equals(path)
                || WebPwaAssets.ICON_192_PATH.equals(path)
                || WebPwaAssets.ICON_512_PATH.equals(path);
    }

    private void sendPublicPwaAsset(HttpExchange exchange, String path, String language) throws IOException {
        if (WebPwaAssets.MANIFEST_PATH.equals(path)) {
            sendStatic(exchange, 200, "application/manifest+json;charset=utf-8",
                    pwaAssets.manifest(language).getBytes(StandardCharsets.UTF_8), "public, max-age=300");
        } else if (WebPwaAssets.SERVICE_WORKER_PATH.equals(path)) {
            exchange.getResponseHeaders().set("Service-Worker-Allowed", "/web/");
            sendStatic(exchange, 200, "application/javascript;charset=utf-8",
                    pwaAssets.serviceWorker().getBytes(StandardCharsets.UTF_8), "no-cache");
        } else if (WebPwaAssets.OFFLINE_PATH.equals(path)) {
            sendStatic(exchange, 200, "text/html;charset=utf-8",
                    pwaAssets.offlinePage(language).getBytes(StandardCharsets.UTF_8), "public, max-age=300");
        } else if (WebPwaAssets.CLIENT_JS_PATH.equals(path)) {
            sendStatic(exchange, 200, "application/javascript;charset=utf-8",
                    pwaAssets.clientScript().getBytes(StandardCharsets.UTF_8), "public, max-age=31536000, immutable");
        } else if (WebPwaAssets.CSS_PATH.equals(path)) {
            sendStatic(exchange, 200, "text/css;charset=utf-8",
                    pwaAssets.stylesheet().getBytes(StandardCharsets.UTF_8), "public, max-age=31536000, immutable");
        } else if (WebPwaAssets.ICON_192_PATH.equals(path)) {
            sendStatic(exchange, 200, "image/png", pwaAssets.icon192(), "public, max-age=31536000, immutable");
        } else if (WebPwaAssets.ICON_512_PATH.equals(path)) {
            sendStatic(exchange, 200, "image/png", pwaAssets.icon512(), "public, max-age=31536000, immutable");
        } else {
            sendText(exchange, 404, t(language, "web.error.not_found"));
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; var out = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 1024))) {
            byte[] buffer = new byte[512];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > maxBytes) throw new IOException("Request body too large");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static Long jsonLong(String json, String field) {
        if (json == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find()) return null;
        try { return Long.parseLong(matcher.group(1)); }
        catch (NumberFormatException ignored) { return null; }
    }

    /** Web catalogue data is never anonymous, even when loopback OPDS itself is configured public. */
    private boolean webAuthorized(HttpExchange exchange, OpdsTokenScope requiredScope) throws IOException {
        String value = exchange.getRequestHeaders().getFirst("Authorization");
        boolean bearer = value != null && value.regionMatches(true, 0, "Bearer ", 0, 7);
        if (bearer || activeSettings.basicAuthEnabled()) return authorized(exchange, requiredScope);
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"MyHomeLib Web\"");
        exchange.getResponseHeaders().set("Connection", "close");
        sendText(exchange, 401, t(requestLanguage(exchange), "web.error.auth_required"));
        return false;
    }

    private boolean healthAuthorized(HttpExchange exchange) throws IOException {
        OpdsServerSettings s = activeSettings;
        if (!activeExposedBeyondLoopback || !s.limits().healthRequiresAuthWhenExposed()) return true;
        if (!s.basicAuthEnabled()) {
            exchange.getResponseHeaders().set("Connection", "close");
            sendText(exchange, 403, t(requestLanguage(exchange), "web.error.health_private"));
            return false;
        }
        return authorized(exchange, OpdsTokenScope.CATALOG_READ);
    }

    private boolean authorized(HttpExchange exchange, OpdsTokenScope requiredScope) throws IOException {
        OpdsServerSettings s = activeSettings;
        String value = exchange.getRequestHeaders().getFirst("Authorization");
        boolean bearerAttempt = value != null && value.regionMatches(true, 0, "Bearer ", 0, 7);

        if (bearerAttempt) {
            String client = clientKey(exchange);
            OpdsRequestLimiter limiter = requestLimiter.get();
            OpdsRequestLimiter.AuthThrottle throttle = limiter.beforeAuthentication(client);
            if (throttle.blocked()) {
                sendThrottled(exchange, throttle.retryAfterSeconds());
                return false;
            }
            String raw = value.substring(7).trim();
            OpdsAccessTokenService.Authorization result = accessTokens == null
                    ? OpdsAccessTokenService.Authorization.INVALID
                    : accessTokens.authorize(raw, requiredScope);
            if (result == OpdsAccessTokenService.Authorization.AUTHORIZED) {
                limiter.authenticationSucceeded(client);
                return true;
            }
            if (result == OpdsAccessTokenService.Authorization.INSUFFICIENT_SCOPE) {
                exchange.getResponseHeaders().set("WWW-Authenticate",
                        "Bearer realm=\"MyHomeLib OPDS\", error=\"insufficient_scope\"");
                exchange.getResponseHeaders().set("Connection", "close");
                sendText(exchange, 403, t(requestLanguage(exchange), "web.error.insufficient_scope"));
                return false;
            }
            OpdsRequestLimiter.AuthThrottle afterFailure = limiter.authenticationFailed(client);
            if (afterFailure.blocked()) sendThrottled(exchange, afterFailure.retryAfterSeconds());
            else {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"MyHomeLib OPDS\"");
                exchange.getResponseHeaders().set("Connection", "close");
                sendText(exchange, 401, t(requestLanguage(exchange), "web.error.invalid_token"));
            }
            return false;
        }

        if (!s.basicAuthEnabled()) {
            // Loopback keeps the historical anonymous mode for local desktop use. Once the server
            // is exposed beyond loopback, TLS protects only the channel; it must not implicitly
            // make the catalogue public. Bearer tokens remain a supported auth mode because they
            // are handled above before this branch.
            if (!activeExposedBeyondLoopback) return true;
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"MyHomeLib OPDS\"");
            exchange.getResponseHeaders().set("Connection", "close");
            sendText(exchange, 401, t(requestLanguage(exchange), "web.error.network_auth_required"));
            return false;
        }

        String client = clientKey(exchange);
        OpdsRequestLimiter limiter = requestLimiter.get();
        OpdsRequestLimiter.AuthThrottle throttle = limiter.beforeAuthentication(client);
        if (throttle.blocked()) {
            sendThrottled(exchange, throttle.retryAfterSeconds());
            log.warn("OPDS authentication throttled for client {}", client);
            return false;
        }

        String[] credentials = decodeBasicCredentials(value);
        boolean usernameMatches = credentials != null
                && MessageDigest.isEqual(credentials[0].getBytes(StandardCharsets.UTF_8), s.username().getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches = credentials != null && OpdsPasswordHash.matches(credentials[1], s.password());
        boolean ok = usernameMatches & passwordMatches;
        if (ok) {
            limiter.authenticationSucceeded(client);
            return true;
        }

        OpdsRequestLimiter.AuthThrottle afterFailure = limiter.authenticationFailed(client);
        log.warn("OPDS authentication failed for client {}", client);
        if (afterFailure.blocked()) {
            sendThrottled(exchange, afterFailure.retryAfterSeconds());
        } else {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"MyHomeLib OPDS\", charset=\"UTF-8\"");
            exchange.getResponseHeaders().set("Connection", "close");
            sendText(exchange, 401, t(requestLanguage(exchange), "web.error.auth_required"));
        }
        return false;
    }

    private void sendThrottled(HttpExchange exchange, long retryAfterSeconds) throws IOException {
        exchange.getResponseHeaders().set("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        exchange.getResponseHeaders().set("Connection", "close");
        sendText(exchange, 429, t(requestLanguage(exchange), "web.error.too_many_auth_failures"));
    }

    private static String clientKey(HttpExchange exchange) {
        if (exchange.getRemoteAddress() == null) return "unknown";
        if (exchange.getRemoteAddress().getAddress() != null) {
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        }
        return Objects.toString(exchange.getRemoteAddress().getHostString(), "unknown");
    }

    private static String[] decodeBasicCredentials(String value) {
        if (value == null || !value.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(value.substring(6).trim());
            String raw = new String(decoded, StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            if (separator < 0) return null;
            return new String[]{raw.substring(0, separator), raw.substring(separator + 1)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static HttpServer createHttpServer(InetSocketAddress address, int backlog, SSLContext sslContext) throws IOException {
        if (sslContext == null) return HttpServer.create(address, backlog);
        HttpsServer https = HttpsServer.create(address, backlog);
        https.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters secure = sslContext.getDefaultSSLParameters();
                secure.setProtocols(supportedTlsProtocols(secure.getProtocols()));
                params.setSSLParameters(secure);
            }
        });
        return https;
    }

    private static SSLContext createSslContext(OpdsTlsSettings tls) throws Exception {
        if (!tls.hasKeyStorePath()) {
            throw new IllegalArgumentException("TLS is enabled but opds.tls.keyStorePath is empty");
        }
        Path path = Path.of(tls.keyStorePath()).toAbsolutePath().normalize();
        Path safePath;
        try {
            safePath = SafeFilePublication.requireSafeRegularFile(path, "OPDS TLS keystore");
        } catch (IOException unsafe) {
            throw new IllegalArgumentException("TLS keystore does not exist or is unsafe: " + path, unsafe);
        }
        char[] password = resolveKeyStorePassword(tls);
        byte[] keyStoreBytes = null;
        try {
            String expectedHash = SafeFilePublication.sha256(safePath, "OPDS TLS keystore snapshot");
            keyStoreBytes = SafeFilePublication.readOwnedBytes(
                    safePath, expectedHash, MAX_TLS_KEYSTORE_BYTES, "OPDS TLS keystore");
            KeyStore keyStore = KeyStore.getInstance(tls.keyStoreType());
            try (InputStream in = new ByteArrayInputStream(keyStoreBytes)) {
                keyStore.load(in, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);
            return ssl;
        } finally {
            Arrays.fill(password, '\0');
            if (keyStoreBytes != null) Arrays.fill(keyStoreBytes, (byte) 0);
        }
    }

    private static char[] resolveKeyStorePassword(OpdsTlsSettings tls) {
        String password = tls.keyStorePassword();
        if (password == null || password.isEmpty()) password = System.getProperty("myhomelib.opds.tls.keyStorePassword", "");
        if (password.isEmpty()) password = Objects.toString(System.getenv("MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD"), "");
        return password.toCharArray();
    }

    private static String[] supportedTlsProtocols(String[] supported) {
        List<String> secure = Arrays.stream(supported)
                .filter(p -> "TLSv1.3".equals(p) || "TLSv1.2".equals(p))
                .toList();
        return secure.isEmpty() ? supported : secure.toArray(String[]::new);
    }

    private static void cleanupLocalResources(HttpServer created, ExecutorService pool) {
        if (created != null) {
            try { created.stop(0); } catch (RuntimeException ignored) { }
        }
        if (pool != null) pool.shutdownNow();
    }

    private static String displayHost(String host) {
        return host != null && host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private String rootFeed(String language) {
        String updated = Instant.now().toString();
        return feedStart("MyHomeLib", "urn:myhomelib:opds:root", "/opds", updated)
                + navigationEntry("authors", t(language, "web.opds.authors"), "/opds/authors")
                + navigationEntry("series", t(language, "web.opds.series"), "/opds/series")
                + navigationEntry("genres", t(language, "web.opds.genres"), "/opds/genres")
                + navigationEntry("search", t(language, "web.opds.search"), "/opds/search?q=")
                + navigationEntry("opds2", "OPDS 2.0", "/opds/v2")
                + "</feed>";
    }

    private String facetFeed(String language, String title, String self, OpdsPage<OpdsFacetDto> page) {
        StringBuilder xml = new StringBuilder(feedStart(title, "urn:myhomelib:opds:" + self, self, Instant.now().toString()));
        paginationLinks(xml, self, page.offset(), page.limit(), page.hasPrevious(), page.hasNext());
        for (OpdsFacetDto item : page.items()) {
            xml.append("<entry><id>urn:myhomelib:facet:").append(escape(item.id())).append("</id>")
                    .append("<title>").append(escape(nonBlank(item.label(), t(language, "web.opds.unnamed")))).append("</title>")
                    .append("<content type=\"text\">").append(escape(t(language, "web.opds.books_count", Long.toString(item.bookCount())))).append("</content>")
                    .append("<link rel=\"subsection\" type=\"").append(ATOM).append("\" href=\"")
                    .append(escapeAttr(self + "/" + encode(item.id()))).append("\"/></entry>");
        }
        return xml.append("</feed>").toString();
    }

    private String booksFeed(String language, String title, String self, OpdsPage<OpdsBookDto> page) {
        StringBuilder xml = new StringBuilder(feedStart(title, "urn:myhomelib:books:" + self, self, Instant.now().toString()));
        paginationLinks(xml, self, page.offset(), page.limit(), page.hasPrevious(), page.hasNext());
        for (OpdsBookDto book : page.items()) xml.append(bookEntry(language, book));
        return xml.append("</feed>").toString();
    }

    private String bookEntry(String language, OpdsBookDto book) {
        StringBuilder xml = new StringBuilder("<entry>");
        xml.append("<id>urn:myhomelib:book:").append(escape(book.id())).append("</id>")
                .append("<title>").append(escape(nonBlank(book.title(), t(language, "web.opds.untitled")))).append("</title>")
                .append("<updated>").append(Instant.now()).append("</updated>");
        if (!blank(book.authors())) xml.append("<author><name>").append(escape(book.authors())).append("</name></author>");
        if (!blank(book.language())) xml.append("<dc:language>").append(escape(book.language())).append("</dc:language>");
        if (book.year() != null) xml.append("<dc:date>").append(book.year()).append("</dc:date>");
        if (!blank(book.series())) xml.append("<category term=\"series\" label=\"").append(escapeAttr(book.series())).append("\"/>");
        if (!blank(book.annotation())) xml.append("<content type=\"text\">").append(escape(book.annotation())).append("</content>");
        xml.append("<link rel=\"alternate\" type=\"").append(ATOM).append("\" href=\"/opds/books/")
                .append(encode(book.id())).append("\"/>");
        if (book.local()) {
            xml.append("<link rel=\"http://opds-spec.org/acquisition/open-access\" type=\"")
                    .append(escapeAttr(mimeType(book))).append("\" href=\"/opds/download/").append(encode(book.id())).append("\"/>");
        }
        return xml.append("</entry>").toString();
    }

    private static String navigationEntry(String id, String title, String href) {
        return "<entry><id>urn:myhomelib:nav:" + escape(id) + "</id><title>" + escape(title)
                + "</title><link rel=\"subsection\" type=\"" + ATOM + "\" href=\"" + escapeAttr(href) + "\"/></entry>";
    }

    private static String feedStart(String title, String id, String self, String updated) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<feed xmlns=\"http://www.w3.org/2005/Atom\" xmlns:dc=\"http://purl.org/dc/terms/\">"
                + "<id>" + escape(id) + "</id><title>" + escape(title) + "</title><updated>" + escape(updated) + "</updated>"
                + "<link rel=\"self\" type=\"" + ATOM + "\" href=\"" + escapeAttr(self) + "\"/>";
    }

    private static void paginationLinks(StringBuilder xml, String path, int offset, int limit, boolean previous, boolean next) {
        String separator = path.contains("?") ? "&" : "?";
        if (previous) xml.append("<link rel=\"previous\" type=\"").append(ATOM).append("\" href=\"")
                .append(escapeAttr(path + separator + "offset=" + Math.max(0, offset - limit) + "&limit=" + limit)).append("\"/>");
        if (next) xml.append("<link rel=\"next\" type=\"").append(ATOM).append("\" href=\"")
                .append(escapeAttr(path + separator + "offset=" + (offset + limit) + "&limit=" + limit)).append("\"/>");
    }

    private void downloadKobo(HttpExchange exchange, KoboBookProjection projection) throws IOException {
        Optional<KoboContentService.Content> opened;
        try {
            opened = koboDownloads.open(projection);
        } catch (Exception conversionFailure) {
            log.warn("Kobo content preparation failed for book {}: {}", projection.bookId(), conversionFailure.getClass().getSimpleName());
            sendJson(exchange, 422, "{\"error\":\"content_conversion_failed\"}");
            return;
        }
        if (opened.isEmpty()) {
            sendJson(exchange, 404, "{\"error\":\"content_not_found\"}");
            return;
        }
        try (KoboContentService.Content download = opened.get()) {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/epub+zip");
            headers.set("Content-Disposition", "attachment; filename*=UTF-8''" + encode(download.fileName()));
            headers.set("Cache-Control", "private, no-store");
            applyBrowserHardening(headers);
            long size = download.size();
            exchange.sendResponseHeaders(200, size > 0 ? size : 0);
            try (OutputStream out = exchange.getResponseBody()) { download.transferTo(out); }
        }
    }

    private void download(HttpExchange exchange, String bookId) throws IOException {
        String language = webText.resolveLanguage(exchange.getRequestHeaders().getFirst("Accept-Language"));
        Optional<OpdsDownloadService.Download> opened;
        try {
            opened = downloads.open(bookId);
        } catch (Exception e) {
            log.warn("Cannot open OPDS book {}", bookId, e);
            sendText(exchange, 500, t(language, "web.error.cannot_open_book"));
            return;
        }
        if (opened.isEmpty()) {
            sendText(exchange, 404, t(language, "web.error.local_file_not_found"));
            return;
        }
        try (OpdsDownloadService.Download download = opened.get()) {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", mime(download.fileName()));
            headers.set("Content-Disposition", "attachment; filename*=UTF-8''" + encode(download.fileName()));
            headers.set("Cache-Control", "no-store");
            applyBrowserHardening(headers);
            exchange.sendResponseHeaders(200, download.size());
            try (OutputStream out = exchange.getResponseBody()) {
                download.transferTo(out);
            }
        } catch (Exception e) {
            // Якщо сталася помилка під час відправки, закриття download вже виконано
            throw e;
        }
    }

    static String mimeType(OpdsBookDto book) {
        String candidate = !blank(book.format()) ? book.format() : book.fileName();
        return mime(candidate);
    }

    private static String mime(String name) {
        String ext = extension(name);
        return switch (ext) {
            case "fb2", "fbd" -> "application/fb2+xml";
            case "epub", "kepub" -> "application/epub+zip";
            case "pdf" -> "application/pdf";
            case "mobi", "azw", "azw3" -> "application/x-mobipocket-ebook";
            case "txt", "text", "md" -> "text/plain;charset=utf-8";
            case "djvu", "djv" -> "image/vnd.djvu";
            default -> "application/octet-stream";
        };
    }

    private static String extension(String value) {
        if (value == null) return "";
        String v = value.toLowerCase(Locale.ROOT).trim();
        int dot = v.lastIndexOf('.');
        return dot >= 0 ? v.substring(dot + 1) : v;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> result = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = decode(eq < 0 ? "" : pair.substring(eq + 1));
            result.putIfAbsent(key, value);
        }
        return result;
    }

    private static int offset(Map<String, String> query) { return integer(query.get("offset"), 0, 0, Integer.MAX_VALUE); }
    private static int limit(Map<String, String> query) { return integer(query.get("limit"), DEFAULT_LIMIT, 1, 100); }
    private static int integer(String value, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, Integer.parseInt(value))); }
        catch (Exception ignored) { return fallback; }
    }

    private static String segmentAfter(String path, String prefix) { return path.length() <= prefix.length() ? "" : path.substring(prefix.length()); }
    private static String normalizePath(String path) { return path == null || path.isBlank() ? "/" : path.replaceAll("/{2,}", "/"); }
    static String encodePathSegment(String value) { return encode(value); }
    private static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String decode(String value) { return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8); }
    private static String nonBlank(String value, String fallback) { return blank(value) ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String escape(String value) { return (value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String escapeAttr(String value) { return escape(value).replace("\"", "&quot;").replace("'", "&apos;"); }

    private String requestLanguage(HttpExchange exchange) {
        return webText.resolveLanguage(exchange == null ? null : exchange.getRequestHeaders().getFirst("Accept-Language"));
    }

    private String t(String language, String key, Object... args) { return webText.text(language, key, args); }

    private static void sendXml(HttpExchange exchange, int code, String body) throws IOException { send(exchange, code, ATOM, body); }
    private static void sendOpds2(HttpExchange exchange, int code, String body) throws IOException { send(exchange, code, Opds2JsonRenderer.TYPE, body); }
    private static void sendJson(HttpExchange exchange, int code, String body) throws IOException { send(exchange, code, "application/json;charset=utf-8", body); }
    private static void sendHtml(HttpExchange exchange, int code, String body) throws IOException {
        sendWithCache(exchange, code, "text/html;charset=utf-8", body.getBytes(StandardCharsets.UTF_8), "private, no-store");
    }
    private static void sendText(HttpExchange exchange, int code, String body) throws IOException { send(exchange, code, "text/plain;charset=utf-8", body); }
    private static void sendTextSafe(HttpExchange exchange, int code, String body) {
        try { sendText(exchange, code, body); } catch (Exception ignored) { }
    }
    private static void send(HttpExchange exchange, int code, String type, String body) throws IOException {
        sendWithCache(exchange, code, type, body.getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private static void sendStatic(HttpExchange exchange, int code, String type, byte[] body, String cacheControl) throws IOException {
        sendWithCache(exchange, code, type, body, cacheControl);
    }

    private static void sendWithCache(HttpExchange exchange, int code, String type, byte[] bytes, String cacheControl) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", cacheControl);
        applyBrowserHardening(headers);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
    private static void applyBrowserHardening(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        headers.set("Content-Security-Policy",
                "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self' 'unsafe-inline'; connect-src 'self'; font-src 'self'; "
                        + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    }

    private static boolean isLoopback(String host) {
        try { return InetAddress.getByName(host).isLoopbackAddress(); } catch (Exception e) { return false; }
    }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
