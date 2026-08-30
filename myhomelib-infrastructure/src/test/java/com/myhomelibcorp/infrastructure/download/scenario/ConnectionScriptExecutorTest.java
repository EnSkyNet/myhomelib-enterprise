package com.myhomelibcorp.infrastructure.download.scenario;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.download.DownloadPayloadValidator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ConnectionScriptExecutorTest {
    static {
        System.setProperty("myhomelib.encryption.key", Base64.getEncoder().encodeToString(new byte[32]));
    }

    @TempDir Path temp;
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void supportsUnicodeGetAndCheckOnEmbeddedServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestURI().getRawPath()).contains("%D0%9A%D0%B8%D1%97%D0%B2%20book");
            byte[] body = "valid payload".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        BookDto book = BookDto.builder().id("1").title("Київ book").fileName("book.txt").build();
        Collection collection = collection(null, null);
        Path target = temp.resolve("book.txt");
        var result = executor(settings()).execute(
                "GET " + baseUrl() + "%TITLE%\nCHECK", book, collection, temp, "book.txt", target,
                false, null, ignored -> { });

        assertThat(Files.readString(result.payload())).isEqualTo("valid payload");
        assertThat(result.checked()).isTrue();
    }

    @Test
    void preservesUpstreamRedirAndResurlSemantics() throws Exception {
        AtomicInteger finalRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            finalRequests.incrementAndGet();
            byte[] body = "book".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        BookDto book = BookDto.builder().id("1").fileName("book.txt").build();
        var result = executor(settings()).execute(
                "GET " + baseUrl() + "login\nREDIR\nGET %RESURL%\nCHECK",
                book, collection(null, null), temp, "book.txt", temp.resolve("book.txt"),
                false, null, ignored -> { });

        assertThat(finalRequests.get()).isEqualTo(2); // first via redirect, second via %RESURL%
        assertThat(Files.readString(result.payload())).isEqualTo("book");
    }

    @Test
    void addAndPostSendMultipartParameters() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/post", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(body).contains("name=\"login\"").contains("alice");
            byte[] response = "book".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        BookDto book = BookDto.builder().id("1").fileName("book.txt").build();
        var result = executor(settings()).execute(
                "ADD login alice\nPOST " + baseUrl() + "post\nCHECK",
                book, collection(null, null), temp, "book.txt", temp.resolve("book.txt"),
                false, null, ignored -> { });

        assertThat(Files.readString(result.payload())).isEqualTo("book");
    }

    @Test
    void permanent404IsNotRetried() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();

        MemorySettings cfg = settings();
        cfg.put("online.retryCount", "3");
        BookDto book = BookDto.builder().id("1").fileName("book.txt").build();

        assertThatThrownBy(() -> executor(cfg).execute(
                "GET " + baseUrl() + "missing", book, collection(null, null), temp, "book.txt",
                temp.resolve("book.txt"), false, null, ignored -> { }))
                .isInstanceOf(DownloadScenarioException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void neverIncludesPassMacroValueInNetworkError() {
        String secret = "super-secret-value";
        BookDto book = BookDto.builder().id("1").fileName("book.txt").build();
        Collection collection = collection("reader", secret);
        var cfg = settings();
        cfg.put("online.retryCount", "0");

        assertThatThrownBy(() -> executor(cfg).execute(
                "GET http://127.0.0.1:1/%PASS%?token=%PASS%",
                book, collection, temp, "book.txt", temp.resolve("book.txt"), false, null, ignored -> { }))
                .isInstanceOf(DownloadScenarioException.class)
                .hasMessageNotContaining(secret)
                .hasMessageContaining("network request failed");
    }

    private ConnectionScriptExecutor executor(ApplicationSettingsPort settings) {
        return new ConnectionScriptExecutor(settings, new DownloadPayloadValidator(mock(ArchiveReader.class)));
    }

    private Collection collection(String user, String password) {
        Collection c = new Collection("c", "Online", temp, "c.db", 1, user, null, baseUrl(), "", "");
        return password == null ? c : c.withEncryptedPassword(password);
    }

    private String baseUrl() {
        if (server == null) return "http://127.0.0.1:1/";
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static MemorySettings settings() { return new MemorySettings(); }

    private static final class MemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            return values.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }
}
