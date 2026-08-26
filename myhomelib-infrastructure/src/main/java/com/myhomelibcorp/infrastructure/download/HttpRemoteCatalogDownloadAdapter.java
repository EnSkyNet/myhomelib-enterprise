package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogPackage;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * HTTP catalog downloader with MyHomeLib server-profile support.
 *
 * <p>The alex80 server exposes a directory, version markers and two update packages.
 * A directory URL must never be saved blindly as an .inpx file: GitHub Pages returns HTML
 * for the directory, which is not a ZIP/INPX archive.</p>
 */
@Component
@Slf4j
public class HttpRemoteCatalogDownloadAdapter implements RemoteCatalogDownloadPort {
    static final String FLIBUSTA_FULL_INPX = "flibusta_online_fb2.inpx";
    static final String FLIBUSTA_FULL_INFO = "flibusta_online_fb2.info";
    static final String FLIBUSTA_FULL_VER = "flibusta_online_fb2.ver";
    static final String FLIBUSTA_FULL_UPDATE = "flibusta_online_fb2.zip";
    static final String FLIBUSTA_EXTRA_INFO = "extra_flibusta_online_fb2.info";
    static final String FLIBUSTA_EXTRA_VER = "extra_flibusta_online_fb2.ver";
    static final String FLIBUSTA_EXTRA_UPDATE = "extra_flibusta_online_fb2.zip";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public Path download(Collection collection, String url, AtomicBoolean cancel, DoubleConsumer progress) throws Exception {
        URI uri = requireHttpUri(url);
        return downloadOne(collection, uri, cancelFlag(cancel), progressSink(progress), 0.0, 1.0).path();
    }

    @Override
    public RemoteCatalogUpdatePlan downloadUpdates(
            Collection collection,
            String source,
            String currentVersion,
            AtomicBoolean cancel,
            DoubleConsumer progress) throws Exception {
        if (collection == null) throw new IllegalArgumentException("Колекцію не задано");
        URI sourceUri = requireHttpUri(source);
        AtomicBoolean flag = cancelFlag(cancel);
        DoubleConsumer sink = progressSink(progress);
        if (flag.get()) throw new IOException("Оновлення скасовано");

        // Explicit files remain supported for custom servers.
        if (isExplicitCatalogFile(sourceUri)) {
            Downloaded downloaded = downloadOne(collection, sourceUri, flag, sink, 0.0, 1.0);
            boolean full = !fileName(sourceUri).toLowerCase(Locale.ROOT).contains("extra");
            return new RemoteCatalogUpdatePlan(
                    List.of(new RemoteCatalogPackage(downloaded.path(), sourceUri.toString(), downloaded.version(), full)),
                    downloaded.version());
        }

        MhlBases mhl = resolveMhlBases(sourceUri);
        if (mhl == null) {
            throw new IllegalArgumentException(
                    "URL вказує не на INPX/ZIP-файл. Для сервера MyHomeLib/Flibusta використовуйте " +
                    "https://alex80.github.io/mhl/download/inpx/ або задайте прямий URL *.inpx");
        }

        // MyHomeLib uses two server roles: the INPX server supplies a complete baseline for
        // collection creation, while the update server supplies versioned full + extra packages.
        String baselineVersion = fetchVersion(collection,
                URI.create(mhl.inpxBase() + FLIBUSTA_FULL_INFO),
                URI.create(mhl.inpxBase() + FLIBUSTA_FULL_VER), flag, true);
        String fullUpdateVersion = fetchVersion(collection,
                URI.create(mhl.updateBase() + FLIBUSTA_FULL_INFO),
                URI.create(mhl.updateBase() + FLIBUSTA_FULL_VER), flag, true);
        String extraVersion = fetchVersion(collection,
                URI.create(mhl.updateBase() + FLIBUSTA_EXTRA_INFO),
                URI.create(mhl.updateBase() + FLIBUSTA_EXTRA_VER), flag, false);

        long current = versionNumber(currentVersion);
        long baseline = versionNumber(baselineVersion);
        long fullUpdate = versionNumber(fullUpdateVersion);
        long extra = versionNumber(extraVersion);
        List<PackageSpec> specs = new ArrayList<>(2);
        long afterFull = current;

        if (current <= 0) {
            // New/unversioned collection: exactly the role of Settings.InpxURL in MyHomeLib.
            specs.add(new PackageSpec(
                    URI.create(mhl.inpxBase() + FLIBUSTA_FULL_INPX), baselineVersion, true));
            afterFull = baseline;
        } else if (fullUpdate > current) {
            // Existing outdated collection: the full package comes from Settings.UpdateURL.
            specs.add(new PackageSpec(
                    URI.create(mhl.updateBase() + FLIBUSTA_FULL_UPDATE), fullUpdateVersion, true));
            afterFull = fullUpdate;
        }

        if (extra > Math.max(current, afterFull)) {
            specs.add(new PackageSpec(
                    URI.create(mhl.updateBase() + FLIBUSTA_EXTRA_UPDATE), extraVersion, false));
        }

        String latest = maxVersion(baselineVersion, fullUpdateVersion, extraVersion, currentVersion);
        if (specs.isEmpty()) {
            sink.accept(1.0);
            log.info("Remote Flibusta catalog is up to date: local={}, baseline={}, fullUpdate={}, extra={}",
                    currentVersion, baselineVersion, fullUpdateVersion, extraVersion);
            return new RemoteCatalogUpdatePlan(List.of(), latest);
        }

        List<RemoteCatalogPackage> packages = new ArrayList<>(specs.size());
        try {
            for (int i = 0; i < specs.size(); i++) {
                if (flag.get()) throw new IOException("Оновлення скасовано");
                PackageSpec spec = specs.get(i);
                double start = (double) i / specs.size();
                double span = 1.0 / specs.size();
                log.info("Downloading catalog package: {} (version={}, full={})",
                        spec.uri(), spec.version(), spec.fullSnapshot());
                Downloaded downloaded = downloadOne(collection, spec.uri(), flag, sink, start, span);
                String version = firstNonBlank(spec.version(), downloaded.version());
                packages.add(new RemoteCatalogPackage(
                        downloaded.path(), spec.uri().toString(), version, spec.fullSnapshot()));
            }
            sink.accept(1.0);
            return new RemoteCatalogUpdatePlan(packages, latest);
        } catch (Exception e) {
            for (RemoteCatalogPackage pkg : packages) deleteQuietly(pkg.file());
            throw e;
        }
    }

    private Downloaded downloadOne(
            Collection collection,
            URI uri,
            AtomicBoolean cancel,
            DoubleConsumer progress,
            double progressStart,
            double progressSpan) throws Exception {
        if (cancel.get()) throw new IOException("Оновлення скасовано");

        HttpResponse<InputStream> response = client.send(
                request(collection, uri).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream ignored = response.body()) { /* close response body */ }
            throw new IOException("HTTP " + response.statusCode() + " для " + uri);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.contains("text/html")) {
            try (InputStream ignored = response.body()) { /* close response body */ }
            throw new IOException("Сервер повернув HTML замість INPX/ZIP: " + uri);
        }

        Path dir = AppPaths.cacheDir().resolve("catalog-updates");
        Files.createDirectories(dir);
        Path part = Files.createTempFile(dir, "catalog-", ".inpx.part");
        Path out = part.resolveSibling(part.getFileName().toString().replace(".part", ""));
        long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        long done = 0;

        try (InputStream in = response.body(); var os = Files.newOutputStream(part)) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (cancel.get() || Thread.currentThread().isInterrupted()) {
                    throw new IOException("Оновлення скасовано");
                }
                if (n == 0) continue;
                os.write(buffer, 0, n);
                done += n;
                if (total > 0) {
                    double local = Math.min(1.0, (double) done / total);
                    progress.accept(Math.min(1.0, progressStart + local * progressSpan));
                }
            }
        } catch (Exception e) {
            deleteQuietly(part);
            throw e;
        }

        try {
            String embeddedVersion = validateCatalogArchive(part, uri);
            Files.move(part, out, StandardCopyOption.REPLACE_EXISTING);
            progress.accept(Math.min(1.0, progressStart + progressSpan));
            return new Downloaded(out, embeddedVersion);
        } catch (Exception e) {
            deleteQuietly(part);
            deleteQuietly(out);
            throw e;
        }
    }

    /** Validate before any importer/database code sees the file. */
    private String validateCatalogArchive(Path file, URI source) throws IOException {
        if (Files.size(file) < 4) throw new IOException("Порожній або пошкоджений каталог: " + source);

        byte[] prefix;
        try (InputStream in = Files.newInputStream(file)) {
            prefix = in.readNBytes(256);
        }
        String head = new String(prefix, StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            throw new IOException("Сервер повернув HTML замість INPX/ZIP: " + source);
        }

        try (ZipFile zip = new ZipFile(file.toFile())) {
            boolean hasInp = zip.stream().anyMatch(e -> !e.isDirectory()
                    && e.getName().toLowerCase(Locale.ROOT).endsWith(".inp"));
            if (!hasInp) {
                throw new IOException("Архів не містить жодного *.inp і не є каталогом MyHomeLib: " + source);
            }
            ZipEntry version = zip.getEntry("version.info");
            if (version == null) return null;
            try (InputStream in = zip.getInputStream(version)) {
                String value = new String(in.readNBytes(128), StandardCharsets.UTF_8).trim();
                return normalizeVersion(value);
            }
        } catch (ZipException e) {
            throw new IOException("Завантажений файл не є коректним INPX/ZIP: " + source, e);
        }
    }

    private String fetchVersion(
            Collection collection,
            URI infoUri,
            URI verUri,
            AtomicBoolean cancel,
            boolean required) throws Exception {
        Exception first = null;
        for (URI uri : List.of(infoUri, verUri)) {
            if (cancel.get()) throw new IOException("Оновлення скасовано");
            try {
                HttpResponse<String> response = client.send(
                        request(collection, uri).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    String version = normalizeVersion(response.body());
                    if (version != null) return version;
                } else {
                    first = new IOException("HTTP " + response.statusCode() + " для " + uri);
                }
            } catch (Exception e) {
                first = e;
            }
        }
        if (required) {
            throw new IOException("Не вдалося отримати версію каталогу Flibusta з сервера", first);
        }
        log.warn("Incremental Flibusta version marker is unavailable: {}", first == null ? "unknown" : first.getMessage());
        return null;
    }

    private HttpRequest.Builder request(Collection collection, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(15))
                .header("User-Agent", "MyHomeLib/1.0.0")
                .GET();
        if (collection != null && collection.getUser() != null && !collection.getUser().isBlank()) {
            String password = "";
            try {
                String decrypted = collection.getDecryptedPassword();
                if (decrypted != null) password = decrypted;
            } catch (Exception ignored) {
                // Preserve the old behaviour: an unreadable stored password does not corrupt the request builder.
            }
            String token = collection.getUser() + ":" + password;
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        }
        return builder;
    }

    private static URI requireHttpUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("URL INPX/сервера не задано");
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некоректний URL каталогу: " + value, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Підтримуються лише HTTP/HTTPS URL");
        }
        return uri;
    }

    private static boolean isExplicitCatalogFile(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return path.endsWith(".inpx") || path.endsWith(".zip");
    }

    /** Recognises alex80's MyHomeLib layout and derives both server roots. */
    static MhlBases resolveMhlBases(URI uri) {
        if (uri == null || uri.getHost() == null || !uri.getHost().equalsIgnoreCase("alex80.github.io")) return null;
        String path = uri.getPath() == null ? "/" : uri.getPath();
        int cut = path.indexOf("/download/inpx");
        if (cut < 0) cut = path.indexOf("/update");
        String rootPath;
        if (cut >= 0) {
            rootPath = path.substring(0, cut);
        } else if (path.equals("/mhl") || path.equals("/mhl/")) {
            rootPath = "/mhl";
        } else {
            return null;
        }
        if (rootPath.isBlank()) rootPath = "/";
        if (!rootPath.endsWith("/")) rootPath += "/";
        String root = uri.getScheme() + "://" + uri.getRawAuthority() + rootPath;
        return new MhlBases(root + "download/inpx/", root + "update/");
    }

    private static String fileName(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isBlank()) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static AtomicBoolean cancelFlag(AtomicBoolean cancel) {
        return cancel == null ? new AtomicBoolean(false) : cancel;
    }

    private static DoubleConsumer progressSink(DoubleConsumer progress) {
        return progress == null ? ignored -> { } : progress;
    }

    private static String normalizeVersion(String value) {
        if (value == null || value.isBlank()) return null;
        String firstLine = value.strip().lines().findFirst().orElse("").trim();
        String digits = firstLine.replaceAll("[^0-9]", "");
        if (digits.length() < 8) return null;
        return digits.substring(0, 8);
    }

    private static long versionNumber(String value) {
        String normalized = normalizeVersion(value);
        if (normalized == null) return 0L;
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String maxVersion(String... values) {
        String best = null;
        long bestValue = 0;
        for (String value : values) {
            long parsed = versionNumber(value);
            if (parsed > bestValue) {
                bestValue = parsed;
                best = normalizeVersion(value);
            }
        }
        return best;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? normalizeVersion(first) : normalizeVersion(second);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private record Downloaded(Path path, String version) { }
    private record PackageSpec(URI uri, String version, boolean fullSnapshot) { }
    record MhlBases(String inpxBase, String updateBase) { }
}
