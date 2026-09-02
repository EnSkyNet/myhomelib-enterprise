package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.*;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
    private volatile OpdsServerSettings activeSettings = OpdsServerSettings.defaults();

    @Override
    public synchronized OpdsServerStatus start(OpdsServerSettings requested) {
        OpdsServerSettings settings = requested == null ? OpdsServerSettings.defaults() : requested;
        stop();
        try {
            InetAddress address = InetAddress.getByName(settings.bindAddress());
            HttpServer created = HttpServer.create(new InetSocketAddress(address, settings.port()), 0);
            created.createContext("/", this::handle);
            ExecutorService pool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("opds-http-", 0).factory());
            executor.set(pool);
            created.setExecutor(pool);
            activeSettings = settings;
            created.start();
            server.set(created);
            int actualPort = created.getAddress().getPort();
            boolean exposed = !address.isLoopbackAddress();
            String host = settings.bindAddress().contains(":") && !settings.bindAddress().startsWith("[")
                    ? "[" + settings.bindAddress() + "]" : settings.bindAddress();
            OpdsServerStatus result = new OpdsServerStatus(true, settings.bindAddress(), actualPort,
                    "http://" + host + ":" + actualPort + "/opds", exposed,
                    exposed ? "OPDS працює; доступ не обмежений localhost" : "OPDS працює локально");
            status.set(result);
            log.info("OPDS started at {}", result.baseUrl());
            return result;
        } catch (Exception e) {
            OpdsServerStatus failed = new OpdsServerStatus(false, settings.bindAddress(), settings.port(), "",
                    !isLoopback(settings.bindAddress()), "Не вдалося запустити OPDS: " + safeMessage(e));
            status.set(failed);
            log.warn("Cannot start OPDS", e);
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
        status.set(OpdsServerStatus.stopped());
    }

    @Override
    public OpdsServerStatus status() { return status.get(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }
            String path = normalizePath(exchange.getRequestURI().getPath());
            if ("/health".equals(path)) {
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

    private boolean authorized(HttpExchange exchange) throws IOException {
        OpdsServerSettings s = activeSettings;
        if (!s.basicAuthEnabled()) return true;
        String value = exchange.getRequestHeaders().getFirst("Authorization");
        String expectedRaw = s.username() + ":" + s.password();
        String expected = "Basic " + Base64.getEncoder().encodeToString(expectedRaw.getBytes(StandardCharsets.UTF_8));
        boolean ok = value != null && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"MyHomeLib OPDS\", charset=\"UTF-8\"");
            sendText(exchange, 401, "Authentication required");
        }
        return ok;
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