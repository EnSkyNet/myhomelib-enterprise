package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpOnlineBookDownloadAdapterTest {
    static {
        System.setProperty("myhomelib.encryption.key", Base64.getEncoder().encodeToString(new byte[32]));
    }
    @TempDir Path temp;
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void coordinatesConcurrentBooksByPhysicalArchivePath() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            requests.incrementAndGet();
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "one-physical-download");
        });

        ArchiveReader archives = mock(ArchiveReader.class);
        when(archives.listEntries(any())).thenReturn(List.of("a.fb2", "b.fb2"));
        when(archives.readEntry(any(), anyString())).thenAnswer(invocation ->
                java.util.Optional.of(new ByteArrayInputStream("book".getBytes(StandardCharsets.UTF_8))));
        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), archives);
        Collection collection = onlineCollection();

        CompletableFuture<?> first = CompletableFuture.runAsync(() -> download(adapter,
                book("a", "a.fb2", "archive.zip", "a.fb2"), collection));
        CompletableFuture<?> second = CompletableFuture.runAsync(() -> download(adapter,
                book("b", "b.fb2", "archive.zip", "b.fb2"), collection));
        CompletableFuture.allOf(first, second).join();

        assertThat(requests.get()).isEqualTo(1);
        assertThat(temp.resolve("archive.zip")).exists();
    }

    @Test
    void retriesTransient503AndThenSucceeds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            int n = requests.incrementAndGet();
            if (n < 3) respond(exchange, 503, "retry");
            else respond(exchange, 200, "ok");
        });

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));
        adapter.download(book("a", "a.txt", "", ""), onlineCollection(), null, null);

        assertThat(requests.get()).isEqualTo(3);
        assertThat(Files.readString(temp.resolve("a.txt"))).isEqualTo("ok");
    }

    @Test
    void doesNotRetryPermanent404() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 404, "missing");
        });

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));
        assertThatThrownBy(() -> adapter.download(book("a", "missing.txt", "", ""), onlineCollection(), null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404");
        assertThat(requests.get()).isEqualTo(1);
    }


    @Test
    void resumesPartialOnlyWithEntityValidatorAndIfRange() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            int n = requests.incrementAndGet();
            if (n == 1) {
                assertThat(exchange.getRequestHeaders().getFirst("Range")).isNull();
                exchange.getResponseHeaders().add("ETag", "\"entity-v1\"");
                byte[] partial = "hello ".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, 11);
                exchange.getResponseBody().write(partial);
                // The handler closes early: the client keeps .part + validator metadata.
                return;
            }
            assertThat(exchange.getRequestHeaders().getFirst("Range")).isEqualTo("bytes=6-");
            assertThat(exchange.getRequestHeaders().getFirst("If-Range")).isEqualTo("\"entity-v1\"");
            exchange.getResponseHeaders().add("ETag", "\"entity-v1\"");
            exchange.getResponseHeaders().add("Content-Range", "bytes 6-10/11");
            respond(exchange, 206, "world");
        });

        ApplicationSettingsPort config = settings();
        when(config.getInt("online.retryCount", 3)).thenReturn(0);
        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(config, mock(ArchiveReader.class));

        assertThatThrownBy(() -> adapter.download(book("a", "a.txt", "", ""), onlineCollection(), null, null))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(temp.resolve("a.txt.part"))).isEqualTo("hello ");
        assertThat(temp.resolve("a.txt.part.meta")).exists();

        adapter.download(book("a", "a.txt", "", ""), onlineCollection(), null, null);

        assertThat(requests.get()).isEqualTo(2);
        assertThat(Files.readString(temp.resolve("a.txt"))).isEqualTo("hello world");
        assertThat(temp.resolve("a.txt.part")).doesNotExist();
        assertThat(temp.resolve("a.txt.part.meta")).doesNotExist();
    }

    @Test
    void stalePartialWithoutValidatorIsRestartedInsteadOfBlindlyAppended() throws Exception {
        Files.writeString(temp.resolve("safe-resume.txt.part"), "old-partial", StandardCharsets.UTF_8);
        server = server(exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Range")).isNull();
            respond(exchange, 200, "new-complete");
        });

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));
        adapter.download(book("s", "safe-resume.txt", "", ""), onlineCollection(), null, null);

        assertThat(Files.readString(temp.resolve("safe-resume.txt"))).isEqualTo("new-complete");
    }

    @Test
    void sendsBasicAuthCredentials() throws Exception {
        server = server(exchange -> {
            String expected = "Basic " + Base64.getEncoder().encodeToString("reader:secret".getBytes(StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo(expected);
            respond(exchange, 200, "ok");
        });

        Collection collection = onlineCollection("reader", "secret", baseUrl());
        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));
        adapter.download(book("auth", "auth.txt", "", ""), collection, null, null);

        assertThat(Files.readString(temp.resolve("auth.txt"))).isEqualTo("ok");
    }

    @Test
    void followsRedirectAndHandlesUnknownContentLength() throws Exception {
        AtomicInteger finalRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect.txt", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final.txt");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final.txt", exchange -> {
            finalRequests.incrementAndGet();
            byte[] bytes = "chunked-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 0); // chunked, no Content-Length
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));
        adapter.download(book("r", "redirect.txt", "", ""), onlineCollection(), null, null);

        assertThat(finalRequests.get()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("redirect.txt"))).isEqualTo("chunked-body");
    }

    @Test
    void expandsUnicodeUrlTemplateForArchiveFileAndEntry() throws Exception {
        server = server(exchange -> {
            String raw = exchange.getRequestURI().getRawPath();
            assertThat(raw).contains("%D0%90%D1%80%D1%85%D1%96%D0%B2%20%D0%BA%D0%BD%D0%B8%D0%B3.zip");
            assertThat(raw).contains("%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0.fb2");
            respond(exchange, 200, "archive-content");
        });
        ArchiveReader archives = mock(ArchiveReader.class);
        when(archives.listEntries(any())).thenReturn(List.of("текст/книга.fb2"));
        when(archives.readEntry(any(), anyString())).thenAnswer(invocation ->
                java.util.Optional.of(new ByteArrayInputStream("book".getBytes(StandardCharsets.UTF_8))));
        String template = baseUrl() + "download/{archive}/{file}/{entry}";
        Collection collection = onlineCollection(null, null, template);
        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), archives);

        adapter.download(book("u", "книга.fb2", "Архів книг.zip", "текст/книга.fb2"), collection, null, null);

        assertThat(temp.resolve("Архів книг.zip")).exists();
    }

    @Test
    void legacyScriptPreambleSuppliesUrlMacroWhenCollectionUrlIsMissing() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/download/321");
            respond(exchange, 200, "plain-book");
        });

        String script = baseUrl() + "\nGET %URL%download/%LIBID%\nCHECK";
        Collection collection = new Collection("online", "Online", temp, null, 1, null, null, null, "", script);
        BookDto book = BookDto.builder()
                .id("legacy-url").libId("321").title("Legacy")
                .fileName("legacy.txt").folder("").archiveEntry("")
                .build();
        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));

        adapter.download(book, collection, null, null);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("legacy.txt"))).isEqualTo("plain-book");
    }

    @Test
    void forceRefreshReplacesExistingCopyOnlyAfterSuccessfulValidation() throws Exception {
        Files.writeString(temp.resolve("fresh.txt"), "old-copy", StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "new-copy");
        });

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));
        adapter.download(book("fresh", "fresh.txt", "", ""), onlineCollection(), null, null, true);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(Files.readString(temp.resolve("fresh.txt"))).isEqualTo("new-copy");
    }

    @Test
    void failedForceRefreshKeepsPreviousLocalCopy() throws Exception {
        Files.writeString(temp.resolve("safe.txt"), "known-good", StandardCharsets.UTF_8);
        server = server(exchange -> respond(exchange, 200, "<html>login required</html>"));

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));
        assertThatThrownBy(() -> adapter.download(book("safe", "safe.txt", "", ""), onlineCollection(), null, null, true))
                .isInstanceOf(IOException.class);

        assertThat(Files.readString(temp.resolve("safe.txt"))).isEqualTo("known-good");
        assertThat(temp.resolve("safe.txt.part")).doesNotExist();
    }

    @Test
    void cancellationKeepsResumablePartialFile() throws Exception {
        Files.writeString(temp.resolve("resume.txt.part"), "partial", StandardCharsets.UTF_8);
        server = server(exchange -> respond(exchange, 200, "ignored"));
        AtomicBoolean cancelled = new AtomicBoolean(true);

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));
        assertThatThrownBy(() -> adapter.download(book("resume", "resume.txt", "", ""), onlineCollection(), cancelled, null))
                .isInstanceOf(HttpOnlineBookDownloadAdapter.DownloadCancelledException.class);

        assertThat(Files.readString(temp.resolve("resume.txt.part"))).isEqualTo("partial");
    }

    @Test
    void redactsSensitiveQueryValuesFromHttpErrors() throws Exception {
        server = server(exchange -> respond(exchange, 404, "missing"));
        String template = baseUrl() + "download/{file}?token=very-secret-token";
        Collection collection = onlineCollection(null, null, template);
        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), mock(ArchiveReader.class));

        assertThatThrownBy(() -> adapter.download(book("secret", "secret.txt", "", ""), collection, null, null))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("very-secret-token")
                .hasMessageContaining("redacted");
    }

    @Test
    void rejectsArchiveWhenRequestedEntryCannotBeResolvedUnambiguously() throws Exception {
        server = server(exchange -> respond(exchange, 200, "archive-bytes"));
        ArchiveReader archives = mock(ArchiveReader.class);
        when(archives.listEntries(any())).thenReturn(List.of("other.fb2", "second.fb2"));
        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(settings(), archives);

        assertThatThrownBy(() -> adapter.download(
                book("a", "a.fb2", "archive.zip", "a.fb2"), onlineCollection(), null, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("однознач");
    }

    @Test
    void flibustaConnectionScriptAcceptsRenamedZipEntryAndReturnsActualEntry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server(exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/b/586491/get");
            respond(exchange, 200, "archive-bytes");
        });

        String actualEntry = "Romanovich_Zemli-chudovishch_1_Zemli-chudovishch.586491.fb2";
        ArchiveReader archives = mock(ArchiveReader.class);
        when(archives.listEntries(any())).thenReturn(List.of(actualEntry));
        when(archives.readEntry(any(), anyString())).thenAnswer(invocation ->
                java.util.Optional.of(new ByteArrayInputStream("<FictionBook/>".getBytes(StandardCharsets.UTF_8))));

        String script = baseUrl() + "\nGET %URL%b/%LIBID%/get\nCHECK";
        Collection collection = new Collection("online", "Online", temp, null, 1, null, null, null, "", script);
        BookDto flibustaBook = BookDto.builder()
                .id("719c5e74-3cf3-3c1c-95c6-1de03831fe48")
                .libId("586491")
                .title("Земли чудовищ")
                .fileName("586491.fb2")
                .folder("online.zip")
                .archiveEntry("586491.fb2")
                .build();

        var result = new HttpOnlineBookDownloadAdapter(settings(), archives)
                .download(flibustaBook, collection, null, null);

        assertThat(requests.get()).isEqualTo(1);
        assertThat(result.archiveEntry()).isEqualTo(actualEntry);
        assertThat(result.physicalPath()).exists();
    }

    private HttpServer server(Handler handler) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            try { handler.handle(exchange); }
            finally { exchange.close(); }
        });
        http.start();
        return http;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private ApplicationSettingsPort settings() {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.get(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            int defaultValue = invocation.getArgument(1);
            if ("online.retryBaseDelayMs".equals(key)) return 100;
            return defaultValue;
        });
        return settings;
    }

    private Collection onlineCollection() {
        return onlineCollection(null, null, baseUrl());
    }

    private Collection onlineCollection(String user, String plainPassword, String url) {
        Collection collection = new Collection("online", "Online", temp, null, 1, user, null, url, "");
        return plainPassword == null ? collection : collection.withEncryptedPassword(plainPassword);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private BookDto book(String id, String file, String folder, String entry) {
        return BookDto.builder().id(id).title(id).fileName(file).folder(folder).archiveEntry(entry).build();
    }

    private void download(HttpOnlineBookDownloadAdapter adapter, BookDto book, Collection collection) {
        try {
            adapter.download(book, collection, null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
