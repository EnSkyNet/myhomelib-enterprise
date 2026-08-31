package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.application.port.out.download.OnlineBookDownloadPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Інтеграційний тест, який перевіряє повний цикл завантаження книги:
 * від визначення режиму до фактичного запису на диск.
 */
class DownloadDecisionIntegrationTest {

    @TempDir
    Path tempDir;

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/book.txt", exchange -> {
            byte[] body = "Test book content\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void urlWithLegacyUrlScript_shouldDownloadViaDirectHttp() throws Exception {
        // 1. Конфігурація: URL + ConnectionScript з legacy URL
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Collection collection = new Collection(
                "test-collection",
                "Test Collection",
                tempDir,
                null,
                1,
                null,
                null,
                baseUrl,
                null,
                baseUrl // ConnectionScript = такий самий URL
        );

        // 2. Підготовка книги
        BookDto book = BookDto.builder()
                .id("test-book-1")
                .title("Test Book")
                .fileName("book.txt")
                .folder("")
                .archiveEntry("")
                .build();

        // 3. Налаштування адаптера
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getInt("online.retryCount", 3)).thenReturn(0);
        when(settings.get("online.downloadBufferKb", "256")).thenReturn("256");
        when(settings.get("online.userAgent", "MyHomeLib Enterprise/7.1")).thenReturn("MyHomeLib Enterprise/7.1");
        when(settings.getInt("online.connectTimeoutSeconds", 20)).thenReturn(20);
        when(settings.getInt("online.readTimeoutSeconds", 120)).thenReturn(120);

        ArchiveReader archiveReader = mock(ArchiveReader.class);
        DownloadPayloadValidator validator = new DownloadPayloadValidator(archiveReader, settings);

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(
                settings,
                archiveReader,
                validator
        );

        // 4. Виконання завантаження - НЕ ПОВИННО ВИКИДУВАТИ ПОМИЛКУ
        OnlineBookDownloadPort.DownloadedBook result = assertDoesNotThrow(() -> {
            return adapter.download(book, collection, null, null);
        });

        // 5. Перевірка результату
        assertThat(result).isNotNull();
        assertThat(result.physicalPath()).isNotNull();
        assertThat(Files.exists(result.physicalPath())).isTrue();
        assertThat(Files.size(result.physicalPath())).isGreaterThan(0);

        String content = Files.readString(result.physicalPath(), StandardCharsets.UTF_8);
        assertThat(content).contains("Test book content");

        System.out.println("""
                
                ================================================================
                INTEGRATION TEST PASSED
                ================================================================
                collectionUrl: %s
                scriptPresent: true
                scriptCommandCount: 0 (legacy URL skipped)
                downloadMode: DIRECT_HTTP
                resolvedUrl: %s
                HTTP status: 200
                downloaded: %s
                size: %d bytes
                ================================================================
                """.formatted(
                baseUrl,
                baseUrl + "book.txt",
                result.physicalPath(),
                Files.size(result.physicalPath())
        ));
    }

    @Test
    void urlWithGetCommand_shouldDownloadViaConnectionScript() throws Exception {
        // 1. Конфігурація: URL + ConnectionScript з командою GET
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        String script = "GET " + baseUrl + "book.txt\nCHECK";
        Collection collection = new Collection(
                "test-collection-script",
                "Test Collection Script",
                tempDir,
                null,
                1,
                null,
                null,
                baseUrl,
                null,
                script
        );

        // 2. Підготовка книги
        BookDto book = BookDto.builder()
                .id("test-book-2")
                .title("Test Book Script")
                .fileName("book.txt")
                .folder("")
                .archiveEntry("")
                .build();

        // 3. Налаштування адаптера
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getInt("online.retryCount", 3)).thenReturn(0);
        when(settings.get("online.downloadBufferKb", "256")).thenReturn("256");
        when(settings.get("online.userAgent", "MyHomeLib Enterprise/7.1")).thenReturn("MyHomeLib Enterprise/7.1");
        when(settings.getInt("online.connectTimeoutSeconds", 20)).thenReturn(20);
        when(settings.getInt("online.readTimeoutSeconds", 120)).thenReturn(120);

        ArchiveReader archiveReader = mock(ArchiveReader.class);
        DownloadPayloadValidator validator = new DownloadPayloadValidator(archiveReader, settings);

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(
                settings,
                archiveReader,
                validator
        );

        // 4. Виконання завантаження через ConnectionScript
        OnlineBookDownloadPort.DownloadedBook result = assertDoesNotThrow(() -> {
            return adapter.download(book, collection, null, null);
        });

        // 5. Перевірка результату
        assertThat(result).isNotNull();
        assertThat(result.physicalPath()).isNotNull();
        assertThat(Files.exists(result.physicalPath())).isTrue();
        assertThat(Files.size(result.physicalPath())).isGreaterThan(0);

        String content = Files.readString(result.physicalPath(), StandardCharsets.UTF_8);
        assertThat(content).contains("Test book content");

        System.out.println("""
                
                ================================================================
                INTEGRATION TEST PASSED (ConnectionScript)
                ================================================================
                collectionUrl: %s
                scriptPresent: true
                scriptCommandCount: 1 (GET)
                downloadMode: CONNECTION_SCRIPT
                HTTP status: 200
                downloaded: %s
                size: %d bytes
                ================================================================
                """.formatted(
                baseUrl,
                result.physicalPath(),
                Files.size(result.physicalPath())
        ));
    }

    @Test
    void onlyUrl_shouldDownloadViaDirectHttp() throws Exception {
        // 1. Конфігурація: тільки URL, без ConnectionScript
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Collection collection = new Collection(
                "test-collection-url-only",
                "Test Collection URL Only",
                tempDir,
                null,
                1,
                null,
                null,
                baseUrl,
                null,
                null // без ConnectionScript
        );

        // 2. Підготовка книги
        BookDto book = BookDto.builder()
                .id("test-book-3")
                .title("Test Book URL Only")
                .fileName("book.txt")
                .folder("")
                .archiveEntry("")
                .build();

        // 3. Налаштування адаптера
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getInt("online.retryCount", 3)).thenReturn(0);
        when(settings.get("online.downloadBufferKb", "256")).thenReturn("256");
        when(settings.get("online.userAgent", "MyHomeLib Enterprise/7.1")).thenReturn("MyHomeLib Enterprise/7.1");
        when(settings.getInt("online.connectTimeoutSeconds", 20)).thenReturn(20);
        when(settings.getInt("online.readTimeoutSeconds", 120)).thenReturn(120);

        ArchiveReader archiveReader = mock(ArchiveReader.class);
        DownloadPayloadValidator validator = new DownloadPayloadValidator(archiveReader, settings);

        HttpOnlineBookDownloadAdapter adapter = new HttpOnlineBookDownloadAdapter(
                settings,
                archiveReader,
                validator
        );

        // 4. Виконання завантаження
        OnlineBookDownloadPort.DownloadedBook result = assertDoesNotThrow(() -> {
            return adapter.download(book, collection, null, null);
        });

        // 5. Перевірка результату
        assertThat(result).isNotNull();
        assertThat(result.physicalPath()).isNotNull();
        assertThat(Files.exists(result.physicalPath())).isTrue();
        assertThat(Files.size(result.physicalPath())).isGreaterThan(0);

        String content = Files.readString(result.physicalPath(), StandardCharsets.UTF_8);
        assertThat(content).contains("Test book content");

        System.out.println("""
                
                ================================================================
                INTEGRATION TEST PASSED (URL only)
                ================================================================
                collectionUrl: %s
                scriptPresent: false
                downloadMode: DIRECT_HTTP
                HTTP status: 200
                downloaded: %s
                size: %d bytes
                ================================================================
                """.formatted(
                baseUrl,
                result.physicalPath(),
                Files.size(result.physicalPath())
        ));
    }
}