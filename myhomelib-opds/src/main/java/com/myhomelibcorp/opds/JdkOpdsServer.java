package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.*;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
@RequiredArgsConstructor
@Slf4j
public class JdkOpdsServer implements com.myhomelibcorp.application.opds.OpdsServerControl {
    private static final String ATOM = "application/atom+xml;profile=opds-catalog;charset=utf-8";
    private static final int DEFAULT_LIMIT = 50;

    private final OpdsCatalogService catalog;
    private final OpdsDownloadService downloads;
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
                    exposed ? "OPDS працює через HTTPS у мережі" :
                            (settings.tls().enabled() ? "OPDS працює локально через HTTPS" : "OPDS працює локально"));
            status.set(result);
            log.info("OPDS started at {}", result.baseUrl());
            return result;
        } catch (Exception e) {
            cleanupLocalResources(created, pool);
            activeSettings = OpdsServerSettings.defaults();
            activeExposedBeyondLoopback = false;
            requestLimiter.set(new OpdsRequestLimiter(OpdsSecurityLimits.defaults()));
            OpdsServerStatus failed = new OpdsServerStatus(false, settings.bindAddress(), settings.port(), "",
                    !isLoopback(settings.bindAddress()), "Не вдалося запустити OPDS: " + safeMessage(e));
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

    private void handle(HttpExchange exchange) throws IOException {
        OpdsRequestLimiter limiter = requestLimiter.get();
        OpdsRequestLimiter.RequestPermit permit = limiter.tryAcquireRequest();
        if (permit == null) {
            try (exchange) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                exchange.getResponseHeaders().set("Connection", "close");
                sendText(exchange, 503, "OPDS server is busy");
            }
            return;
        }

        try (exchange; permit) {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    exchange.getResponseHeaders().set("Connection", "close");
                    sendText(exchange, 405, "Method Not Allowed");
                    return;
                }
                // GET requests do not use a request body; close it immediately to avoid retaining resources.
                exchange.getRequestBody().close();

                String path = normalizePath(exchange.getRequestURI().getPath());
                if ("/health".equals(path)) {
                    if (!healthAuthorized(exchange)) return;
                    sendJson(exchange, 200, "{\"status\":\"UP\",\"opds\":\"running\"}");
                    return;
                }
                if (!path.startsWith("/opds")) {
                    sendText(exchange, 404, "Not Found");
                    return;
                }
                if (!authorized(exchange)) return;

                Map<String, String> query = query(exchange.getRequestURI());
                if ("/opds".equals(path) || "/opds/".equals(path)) {
                    sendXml(exchange, 200, rootFeed());
                } else if ("/opds/authors".equals(path)) {
                    sendXml(exchange, 200, facetFeed("Автори", "/opds/authors", catalog.authors(offset(query), limit(query))));
                } else if (path.startsWith("/opds/authors/")) {
                    String id = decode(segmentAfter(path, "/opds/authors/"));
                    sendXml(exchange, 200, booksFeed("Книги автора", "/opds/authors/" + encode(id),
                            catalog.books(new OpdsBookQuery(id, "", "", "", offset(query), limit(query)))));
                } else if ("/opds/series".equals(path)) {
                    sendXml(exchange, 200, facetFeed("Серії", "/opds/series", catalog.series(offset(query), limit(query))));
                } else if (path.startsWith("/opds/series/")) {
                    String id = decode(segmentAfter(path, "/opds/series/"));
                    sendXml(exchange, 200, booksFeed("Книги серії", "/opds/series/" + encode(id),
                            catalog.books(new OpdsBookQuery("", id, "", "", offset(query), limit(query)))));
                } else if ("/opds/genres".equals(path)) {
                    sendXml(exchange, 200, facetFeed("Жанри", "/opds/genres", catalog.genres(offset(query), limit(query))));
                } else if (path.startsWith("/opds/genres/")) {
                    String id = decode(segmentAfter(path, "/opds/genres/"));
                    sendXml(exchange, 200, booksFeed("Книги жанру", "/opds/genres/" + encode(id),
                            catalog.books(new OpdsBookQuery("", "", id, "", offset(query), limit(query)))));
                } else if ("/opds/search".equals(path)) {
                    String search = query.getOrDefault("q", "");
                    sendXml(exchange, 200, booksFeed("Пошук: " + search, "/opds/search?q=" + encode(search),
                            catalog.books(new OpdsBookQuery("", "", "", search, offset(query), limit(query)))));
                } else if (path.startsWith("/opds/books/")) {
                    String id = decode(segmentAfter(path, "/opds/books/"));
                    var book = catalog.book(id);
                    if (book.isEmpty()) sendText(exchange, 404, "Book not found");
                    else sendXml(exchange, 200, bookEntry(book.get()));
                } else if (path.startsWith("/opds/download/")) {
                    download(exchange, decode(segmentAfter(path, "/opds/download/")));
                } else {
                    sendText(exchange, 404, "Not Found");
                }
            } catch (IllegalStateException e) {
                sendTextSafe(exchange, 503, "No active collection");
            } catch (Exception e) {
                log.warn("OPDS request failed: {}", exchange.getRequestURI(), e);
                sendTextSafe(exchange, 500, "Internal Server Error");
            }
        }
    }

    private boolean healthAuthorized(HttpExchange exchange) throws IOException {
        OpdsServerSettings s = activeSettings;
        if (!activeExposedBeyondLoopback || !s.limits().healthRequiresAuthWhenExposed()) return true;
        if (!s.basicAuthEnabled()) {
            exchange.getResponseHeaders().set("Connection", "close");
            sendText(exchange, 403, "Health endpoint is private when OPDS is exposed");
            return false;
        }
        return authorized(exchange);
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        OpdsServerSettings s = activeSettings;
        if (!s.basicAuthEnabled()) return true;

        String client = clientKey(exchange);
        OpdsRequestLimiter limiter = requestLimiter.get();
        OpdsRequestLimiter.AuthThrottle throttle = limiter.beforeAuthentication(client);
        if (throttle.blocked()) {
            sendThrottled(exchange, throttle.retryAfterSeconds());
            log.warn("OPDS authentication throttled for client {}", client);
            return false;
        }

        String value = exchange.getRequestHeaders().getFirst("Authorization");
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
            sendText(exchange, 401, "Authentication required");
        }
        return false;
    }

    private static void sendThrottled(HttpExchange exchange, long retryAfterSeconds) throws IOException {
        exchange.getResponseHeaders().set("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
        exchange.getResponseHeaders().set("Connection", "close");
        sendText(exchange, 429, "Too many authentication failures");
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
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("TLS keystore does not exist: " + path);
        }
        char[] password = resolveKeyStorePassword(tls);
        try {
            KeyStore keyStore = KeyStore.getInstance(tls.keyStoreType());
            try (InputStream in = Files.newInputStream(path)) {
                keyStore.load(in, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);
            return ssl;
        } finally {
            Arrays.fill(password, '\0');
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

    private String rootFeed() {
        String updated = Instant.now().toString();
        return feedStart("MyHomeLib", "urn:myhomelib:opds:root", "/opds", updated)
                + navigationEntry("authors", "Автори", "/opds/authors")
                + navigationEntry("series", "Серії", "/opds/series")
                + navigationEntry("genres", "Жанри", "/opds/genres")
                + navigationEntry("search", "Пошук", "/opds/search?q=")
                + "</feed>";
    }

    private String facetFeed(String title, String self, OpdsPage<OpdsFacetDto> page) {
        StringBuilder xml = new StringBuilder(feedStart(title, "urn:myhomelib:opds:" + self, self, Instant.now().toString()));
        paginationLinks(xml, self, page.offset(), page.limit(), page.hasPrevious(), page.hasNext());
        for (OpdsFacetDto item : page.items()) {
            xml.append("<entry><id>urn:myhomelib:facet:").append(escape(item.id())).append("</id>")
                    .append("<title>").append(escape(item.label())).append("</title>")
                    .append("<content type=\"text\">").append(item.bookCount()).append(" книг</content>")
                    .append("<link rel=\"subsection\" type=\"").append(ATOM).append("\" href=\"")
                    .append(escapeAttr(self + "/" + encode(item.id()))).append("\"/></entry>");
        }
        return xml.append("</feed>").toString();
    }

    private String booksFeed(String title, String self, OpdsPage<OpdsBookDto> page) {
        StringBuilder xml = new StringBuilder(feedStart(title, "urn:myhomelib:books:" + self, self, Instant.now().toString()));
        paginationLinks(xml, self, page.offset(), page.limit(), page.hasPrevious(), page.hasNext());
        for (OpdsBookDto book : page.items()) xml.append(bookEntry(book));
        return xml.append("</feed>").toString();
    }

    private String bookEntry(OpdsBookDto book) {
        StringBuilder xml = new StringBuilder("<entry>");
        xml.append("<id>urn:myhomelib:book:").append(escape(book.id())).append("</id>")
                .append("<title>").append(escape(book.title())).append("</title>")
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
                    .append(escapeAttr(mime(book))).append("\" href=\"/opds/download/").append(encode(book.id())).append("\"/>");
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

    private void download(HttpExchange exchange, String bookId) throws IOException {
        Optional<OpdsDownloadService.Download> opened;
        try {
            opened = downloads.open(bookId);
        } catch (Exception e) {
            sendText(exchange, 500, "Cannot open book: " + safeMessage(e));
            return;
        }
        if (opened.isEmpty()) {
            sendText(exchange, 404, "Local book file not found");
            return;
        }
        try (OpdsDownloadService.Download download = opened.get()) {
            var path = download.content().path();
            long size = Files.size(path);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", mime(download.fileName()));
            headers.set("Content-Disposition", "attachment; filename*=UTF-8''" + encode(download.fileName()));
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, size);
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(path, out);
            }
        } catch (Exception e) {
            // Якщо сталася помилка під час відправки, закриття download вже виконано
            throw e;
        }
    }

    private static String mime(OpdsBookDto book) {
        String candidate = !blank(book.format()) ? book.format() : book.fileName();
        return mime(candidate);
    }

    private static String mime(String name) {
        String ext = extension(name);
        return switch (ext) {
            case "fb2", "fbd" -> "application/fb2+xml";
            case "epub" -> "application/epub+zip";
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
    private static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String decode(String value) { return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String escape(String value) { return (value == null ? "" : value).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String escapeAttr(String value) { return escape(value).replace("\"", "&quot;").replace("'", "&apos;"); }

    private static void sendXml(HttpExchange exchange, int code, String body) throws IOException { send(exchange, code, ATOM, body); }
    private static void sendJson(HttpExchange exchange, int code, String body) throws IOException { send(exchange, code, "application/json;charset=utf-8", body); }
    private static void sendText(HttpExchange exchange, int code, String body) throws IOException { send(exchange, code, "text/plain;charset=utf-8", body); }
    private static void sendTextSafe(HttpExchange exchange, int code, String body) {
        try { sendText(exchange, code, body); } catch (Exception ignored) { }
    }
    private static void send(HttpExchange exchange, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
    private static boolean isLoopback(String host) {
        try { return InetAddress.getByName(host).isLoopbackAddress(); } catch (Exception e) { return false; }
    }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}