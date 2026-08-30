package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.application.port.out.download.RemoteDownloadProgress;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Properties;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRemoteCatalogDownloadAdapterTest {
    @TempDir Path temp;
    private HttpServer server;

    @BeforeEach
    void useIsolatedDataDirectory() {
        System.setProperty("myhomelib.dataDir", temp.toString());
    }

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void resolvesAlex80InpxDirectoryToBothMyHomeLibServerRoots() {
        HttpRemoteCatalogDownloadAdapter.MhlBases bases = HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://alex80.github.io/mhl/download/inpx/"));

        assertThat(bases).isNotNull();
        assertThat(bases.inpxBase()).isEqualTo("https://alex80.github.io/mhl/download/inpx/");
        assertThat(bases.updateBase()).isEqualTo("https://alex80.github.io/mhl/update/");
    }

    @Test
    void resolvesAlex80UpdateDirectoryAndProjectRootTheSameWay() {
        HttpRemoteCatalogDownloadAdapter.MhlBases update = HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://alex80.github.io/mhl/update/"));
        HttpRemoteCatalogDownloadAdapter.MhlBases root = HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://alex80.github.io/mhl/"));

        assertThat(update).isEqualTo(root);
        assertThat(root.inpxBase()).isEqualTo("https://alex80.github.io/mhl/download/inpx/");
        assertThat(root.updateBase()).isEqualTo("https://alex80.github.io/mhl/update/");
    }

    @Test
    void doesNotTreatAnArbitraryDirectoryAsAMyHomeLibServer() {
        assertThat(HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://example.test/mhl/download/inpx/"))).isNull();
        assertThat(HttpRemoteCatalogDownloadAdapter.resolveMhlBases(
                URI.create("https://alex80.github.io/something-else/"))).isNull();
    }

    @Test
    void resumesPartWithRangeAndCarriesValidatorsAndHash() throws Exception {
        byte[] archive = validInpx("20260827");
        AtomicReference<String> range = new AtomicReference<>();
        server = start(exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            assertThat(exchange.getRequestHeaders().getFirst("If-Range")).isEqualTo("\"catalog-v7\"");
            int offset = Integer.parseInt(range.get().substring("bytes=".length(), range.get().length() - 1));
            byte[] tail = Arrays.copyOfRange(archive, offset, archive.length);
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.getResponseHeaders().add("Content-Range", "bytes " + offset + "-" + (archive.length - 1) + "/" + archive.length);
            exchange.getResponseHeaders().add("ETag", "\"catalog-v7\"");
            exchange.getResponseHeaders().add("Last-Modified", "Thu, 27 Aug 2026 12:00:00 GMT");
            respond(exchange, 206, tail);
        });

        String url = baseUrl() + "catalog.inpx";
        int prefix = Math.min(32, archive.length / 2);
        Path part = temp.resolve("cache/catalog-updates/catalog-" + shortHash(url) + ".inpx.part");
        Files.createDirectories(part.getParent());
        Files.write(part, Arrays.copyOf(archive, prefix));
        writeResumeMetadata(part.resolveSibling(part.getFileName() + ".meta"), url, "\"catalog-v7\"");

        Collection collection = new Collection("c1", "Online", temp, null, 1, null, null, url, "");
        AtomicReference<RemoteDownloadProgress> byteProgress = new AtomicReference<>();
        RemoteCatalogUpdatePlan plan = new HttpRemoteCatalogDownloadAdapter().downloadUpdates(
                collection, url, "", new AtomicBoolean(false), ignored -> {}, byteProgress::set);

        assertThat(range.get()).isEqualTo("bytes=" + prefix + "-");
        assertThat(plan.packages()).hasSize(1);
        var pkg = plan.packages().getFirst();
        assertThat(Files.readAllBytes(pkg.file())).isEqualTo(archive);
        assertThat(pkg.version()).isEqualTo("20260827");
        assertThat(pkg.metadata().etag()).isEqualTo("\"catalog-v7\"");
        assertThat(pkg.metadata().lastModified()).contains("27 Aug 2026");
        assertThat(pkg.metadata().sha256()).hasSize(64);
        assertThat(pkg.metadata().contentLength()).isEqualTo(archive.length);
        assertThat(byteProgress.get()).isNotNull();
        assertThat(byteProgress.get().bytesProcessed()).isEqualTo(archive.length);
        assertThat(byteProgress.get().bytesTotal()).isEqualTo(archive.length);
        assertThat(byteProgress.get().currentItem()).doesNotContain("password=").doesNotContain("token=");
        assertThat(part).doesNotExist();
    }

    @Test
    void stalePartialWithoutValidatorIsDiscardedInsteadOfBlindlyAppended() throws Exception {
        byte[] archive = validInpx("20260828");
        AtomicReference<String> range = new AtomicReference<>();
        server = start(exchange -> {
            range.set(exchange.getRequestHeaders().getFirst("Range"));
            exchange.getResponseHeaders().add("ETag", "\"catalog-v2\"");
            respond(exchange, 200, archive);
        });

        String url = baseUrl() + "catalog.inpx";
        Path part = temp.resolve("cache/catalog-updates/catalog-" + shortHash(url) + ".inpx.part");
        Files.createDirectories(part.getParent());
        Files.writeString(part, "stale-old-revision", StandardCharsets.UTF_8);

        Collection collection = new Collection("c1", "Online", temp, null, 1, null, null, url, "");
        RemoteCatalogUpdatePlan plan = new HttpRemoteCatalogDownloadAdapter().downloadUpdates(
                collection, url, "", new AtomicBoolean(false), ignored -> {});

        assertThat(range.get()).isNull();
        assertThat(Files.readAllBytes(plan.packages().getFirst().file())).isEqualTo(archive);
        assertThat(part).doesNotExist();
        assertThat(part.resolveSibling(part.getFileName() + ".meta")).doesNotExist();
    }

    @Test
    void rejectsHtmlBeforeImporterCanSeeIt() throws Exception {
        server = start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            respond(exchange, 200, "<html><body>error</body></html>".getBytes(StandardCharsets.UTF_8));
        });
        String url = baseUrl() + "catalog.inpx";

        assertThatThrownBy(() -> new HttpRemoteCatalogDownloadAdapter().download(
                null, url, new AtomicBoolean(false), ignored -> {}))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTML");
    }

    private HttpServer start(Handler handler) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            try { handler.handle(exchange); }
            finally { exchange.close(); }
        });
        http.start();
        return http;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static byte[] validInpx(String version) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("books.inp"));
            zip.write("1\004Title\004\004\004\004\004\004\004fb2\004\0040\004ru\0040\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("version.info"));
            zip.write(version.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void writeResumeMetadata(Path meta, String sourceUrl, String validator) throws Exception {
        Properties properties = new Properties();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        properties.setProperty("sourceSha256", HexFormat.of().formatHex(md.digest(sourceUrl.getBytes(StandardCharsets.UTF_8))));
        properties.setProperty("validator", validator);
        try (var out = Files.newOutputStream(meta)) {
            properties.store(out, "test resume metadata");
        }
    }

    private static String shortHash(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 20);
    }

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
