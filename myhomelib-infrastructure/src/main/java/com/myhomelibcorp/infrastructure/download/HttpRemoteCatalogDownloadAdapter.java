package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.catalog.CatalogSourceProfile;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogPackage;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.application.port.out.download.RemoteDownloadMetadata;
import com.myhomelibcorp.application.port.out.download.RemoteDownloadProgress;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.Sha256Support;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** HTTP downloader for validated, resumable catalog updates. */
@Component
@Slf4j
public class HttpRemoteCatalogDownloadAdapter implements RemoteCatalogDownloadPort {
    private static final CatalogSourceProfile FLIBUSTA = CatalogSourceProfile.FLIBUSTA_MHL;

    // Package-visible aliases kept for focused regression tests.
    static final String FLIBUSTA_FULL_INPX = FLIBUSTA.baselineFile();
    static final String FLIBUSTA_FULL_INFO = FLIBUSTA.fullVersionEndpoint();
    static final String FLIBUSTA_FULL_UPDATE = FLIBUSTA.fullUpdateFile();
    static final String FLIBUSTA_EXTRA_INFO = FLIBUSTA.incrementalVersionEndpoint();
    static final String FLIBUSTA_EXTRA_UPDATE = FLIBUSTA.incrementalUpdateFile();

    private static final int MAX_ATTEMPTS = 4;

    private final ApplicationSettingsPort settings;
    private final OnlineHttpPolicy httpPolicy;
    private final HttpClient client;
    private final OnlineRequestLimiter requestLimiter;

    /** Compatibility constructor for focused tests that do not need persisted settings. */
    public HttpRemoteCatalogDownloadAdapter() {
        this(new ApplicationSettingsPort() {
            @Override public String get(String key, String defaultValue) { return defaultValue; }
            @Override public void put(String key, String value) { }
            @Override public void remove(String key) { }
            @Override public java.util.Map<String, String> findByPrefix(String prefix) { return java.util.Map.of(); }
        });
    }

    public HttpRemoteCatalogDownloadAdapter(ApplicationSettingsPort settings) {
        this(settings, new OnlineRequestLimiter(settings));
    }

    @Autowired
    public HttpRemoteCatalogDownloadAdapter(ApplicationSettingsPort settings, OnlineRequestLimiter requestLimiter) {
        this.settings = settings;
        this.requestLimiter = requestLimiter;
        this.httpPolicy = new OnlineHttpPolicy(settings);
        this.client = httpPolicy.create(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    public Path download(Collection collection, String url, AtomicBoolean cancel, DoubleConsumer progress) throws Exception {
        URI uri = requireHttpUri(url);
        return downloadOne(collection, uri, cancelFlag(cancel), progressSink(progress), p -> { }, 0.0, 1.0).path();
    }

    public RemoteCatalogUpdatePlan downloadUpdates(
            Collection collection,
            String source,
            String currentVersion,
            AtomicBoolean cancel,
            DoubleConsumer progress) throws Exception {
        return downloadUpdates(collection, source, currentVersion, cancel, progress, p -> { });
    }

    @Override
    public RemoteCatalogUpdatePlan downloadUpdates(
            Collection collection,
            String source,
            String currentVersion,
            AtomicBoolean cancel,
            DoubleConsumer progress,
            Consumer<RemoteDownloadProgress> detailedProgress) throws Exception {
        if (collection == null) throw new IllegalArgumentException("Колекцію не задано");
        URI sourceUri = requireHttpUri(source);
        AtomicBoolean flag = cancelFlag(cancel);
        DoubleConsumer sink = progressSink(progress);
        Consumer<RemoteDownloadProgress> detailSink = detailedProgress == null ? p -> { } : detailedProgress;
        if (flag.get()) throw new IOException("Оновлення скасовано");

        if (isExplicitCatalogFile(sourceUri)) {
            Downloaded downloaded = downloadOne(collection, sourceUri, flag, sink, detailSink, 0.0, 1.0);
            boolean full = !fileName(sourceUri).toLowerCase(Locale.ROOT).contains("extra");
            return new RemoteCatalogUpdatePlan(
                    List.of(new RemoteCatalogPackage(downloaded.path(), sourceUri.toString(), downloaded.version(), full, downloaded.metadata())),
                    downloaded.version());
        }

        MhlBases mhl = resolveMhlBases(sourceUri);
        if (mhl == null) {
            throw new IllegalArgumentException(
                    "URL вказує не на INPX/ZIP-файл. Для MyHomeLib/Flibusta використовуйте " +
                    FLIBUSTA.baselineUrl() + " або прямий URL *.inpx/*.zip");
        }

        long current = versionNumber(currentVersion);
        // Historical MyHomeLib servers used version marker files in /update/. Some mirrors
        // (including alex80.github.io as observed in 2026) keep the baseline INPX endpoint
        // available while those marker files return 404. Markers are therefore hints, not a
        // prerequisite for a safe update. When both are unavailable we fall back to the
        // canonical full INPX and use its embedded version.info.
        String fullVersion = fetchVersion(collection, URI.create(mhl.updateBase() + FLIBUSTA.fullVersionEndpoint()), flag, false);
        String extraVersion = fetchVersion(collection, URI.create(mhl.updateBase() + FLIBUSTA.incrementalVersionEndpoint()), flag, false);
        long full = versionNumber(fullVersion);
        long extra = versionNumber(extraVersion);
        List<RemoteCatalogPackage> packages = new ArrayList<>(2);

        try {
            if (fullVersion == null && extraVersion == null) {
                URI baselineUri = URI.create(mhl.inpxBase() + FLIBUSTA.baselineFile());
                log.warn("Flibusta update markers are unavailable; falling back to full INPX: {}",
                        SensitiveDataSanitizer.sanitizeUri(baselineUri));
                Downloaded baseline = downloadOne(collection, baselineUri, flag, sink, detailSink, 0.0, 1.0);
                String baselineVersion = normalizeVersion(baseline.version());
                long baselineNumber = versionNumber(baselineVersion);

                // If version.info is present and is not newer than the applied catalog, avoid
                // re-importing the same full snapshot. If it is absent, importing is safer than
                // incorrectly reporting UP_TO_DATE because the content may have changed.
                if (current <= 0 || baselineNumber <= 0 || baselineNumber > current) {
                    packages.add(new RemoteCatalogPackage(
                            baseline.path(), baselineUri.toString(), baselineVersion, true, baseline.metadata()));
                } else {
                    deleteQuietly(baseline.path());
                    sink.accept(1.0);
                    log.info("Remote Flibusta baseline is not newer: local={}, baseline={}",
                            currentVersion, baselineVersion);
                }
                return new RemoteCatalogUpdatePlan(
                        packages, maxVersion(currentVersion, baselineVersion, null));
            }

            long afterFull = current;
            if (current <= 0) {
                URI baselineUri = URI.create(mhl.inpxBase() + FLIBUSTA.baselineFile());
                Downloaded baseline = downloadOne(collection, baselineUri, flag, sink, detailSink, 0.0, 0.60);
                String baselineVersion = normalizeVersion(baseline.version());
                packages.add(new RemoteCatalogPackage(baseline.path(), baselineUri.toString(), baselineVersion, true, baseline.metadata()));
                afterFull = versionNumber(baselineVersion);
                // If the baseline does not carry version.info (or is older), do not assume it is at the
                // remote full version: apply the official full package before any extra delta.
                if (full > afterFull) {
                    URI fullUri = URI.create(mhl.updateBase() + FLIBUSTA.fullUpdateFile());
                    Downloaded downloaded = downloadOne(collection, fullUri, flag, sink, detailSink, 0.60, 0.25);
                    String version = firstNonBlank(fullVersion, downloaded.version());
                    packages.add(new RemoteCatalogPackage(downloaded.path(), fullUri.toString(), version, true, downloaded.metadata()));
                    afterFull = versionNumber(version);
                }
            } else if (full > current) {
                URI fullUri = URI.create(mhl.updateBase() + FLIBUSTA.fullUpdateFile());
                Downloaded downloaded = downloadOne(collection, fullUri, flag, sink, detailSink, 0.0, extra > full ? 0.80 : 1.0);
                String version = firstNonBlank(fullVersion, downloaded.version());
                packages.add(new RemoteCatalogPackage(downloaded.path(), fullUri.toString(), version, true, downloaded.metadata()));
                afterFull = versionNumber(version);
            }

            if (extra > Math.max(current, afterFull)) {
                URI extraUri = URI.create(mhl.updateBase() + FLIBUSTA.incrementalUpdateFile());
                double start = packages.isEmpty() ? 0.0 : (current <= 0 ? 0.85 : 0.80);
                Downloaded downloaded = downloadOne(collection, extraUri, flag, sink, detailSink, start, 1.0 - start);
                String version = firstNonBlank(extraVersion, downloaded.version());
                packages.add(new RemoteCatalogPackage(downloaded.path(), extraUri.toString(), version, false, downloaded.metadata()));
            }

            String latest = maxVersion(currentVersion, fullVersion, extraVersion,
                    packages.stream().map(RemoteCatalogPackage::version).toArray(String[]::new));
            if (packages.isEmpty()) {
                sink.accept(1.0);
                log.info("Remote Flibusta catalog is up to date: local={}, full={}, extra={}", currentVersion, fullVersion, extraVersion);
            }
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
            Consumer<RemoteDownloadProgress> detailedProgress,
            double progressStart,
            double progressSpan) throws Exception {
        if (cancel.get()) throw new IOException("Оновлення скасовано");
        Path dir = AppPaths.cacheDir().resolve("catalog-updates");
        Files.createDirectories(dir);
        String key = shortHash(uri.toString());
        Path part = dir.resolve("catalog-" + key + ".inpx.part");
        Path partMeta = dir.resolve("catalog-" + key + ".inpx.part.meta");
        Path out = dir.resolve("catalog-" + key + ".inpx");
        Exception last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (cancel.get() || Thread.currentThread().isInterrupted()) throw new IOException("Оновлення скасовано");
            long offset = Files.exists(part) ? Files.size(part) : 0L;
            HttpResumeSupport.ResumeMetadata resume = offset > 0 ? HttpResumeSupport.read(partMeta, uri) : null;
            if (offset > 0 && (resume == null || resume.validator().isBlank())) {
                // Never append bytes from an unvalidated older representation to a newer catalog.
                Files.deleteIfExists(part);
                Files.deleteIfExists(partMeta);
                offset = 0L;
                resume = null;
            }
            try (OnlineRequestLimiter.Permit ignored = requestLimiter.acquire(uri, cancel)) {
                HttpRequest.Builder builder = request(collection, uri);
                if (offset > 0) {
                    builder.header("Range", "bytes=" + offset + "-");
                    builder.header("If-Range", resume.validator());
                }
                HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (offset > 0 && status == 206) {
                    HttpResumeSupport.validateContentRange(response, offset, uri);
                } else if (status == 200) {
                    if (offset > 0) {
                        // Server ignored Range: restart safely instead of appending duplicate bytes.
                        Files.deleteIfExists(part);
                        Files.deleteIfExists(partMeta);
                        offset = 0;
                    }
                } else if (status == 416 && offset > 0) {
                    closeQuietly(response.body());
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    throw new RetryableDownloadException("HTTP 416 для resume " + SensitiveDataSanitizer.sanitizeUri(uri));
                } else if (OnlineRetryPolicy.isRetryableStatus(status)) {
                    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                    closeQuietly(response.body());
                    throw new RetryableDownloadException(
                            "HTTP " + status + " для " + SensitiveDataSanitizer.sanitizeUri(uri), retryAfter);
                } else if (status < 200 || status >= 300) {
                    closeQuietly(response.body());
                    throw new IOException("HTTP " + status + " для " + SensitiveDataSanitizer.sanitizeUri(uri));
                }

                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if (contentType.contains("text/html")) {
                    closeQuietly(response.body());
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    throw new IOException("Сервер повернув HTML замість INPX/ZIP: " + SensitiveDataSanitizer.sanitizeUri(uri));
                }

                HttpResumeSupport.write(partMeta, uri, response);
                long expectedTotal = HttpResumeSupport.expectedTotal(response, offset);
                long done = offset;
                OnlineProgressThrottle progressThrottle = new OnlineProgressThrottle(done);
                emitDownloadProgress(detailedProgress, done, expectedTotal, uri, progressStart, progressSpan);
                var options = offset > 0
                        ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                        : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
                try (InputStream in = response.body(); var os = Files.newOutputStream(part, options)) {
                    byte[] buffer = new byte[128 * 1024];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        if (cancel.get() || Thread.currentThread().isInterrupted()) {
                            // Keep .part intentionally: a later attempt can resume it.
                            throw new IOException("Оновлення скасовано");
                        }
                        if (n == 0) continue;
                        os.write(buffer, 0, n);
                        done += n;
                        if (progressThrottle.shouldEmit(done, expectedTotal)) {
                            if (expectedTotal > 0) {
                                double local = Math.min(1.0, (double) done / expectedTotal);
                                progress.accept(Math.min(1.0, progressStart + local * progressSpan));
                            }
                            emitDownloadProgress(detailedProgress, done, expectedTotal, uri, progressStart, progressSpan);
                        }
                    }
                }

                long actual = Files.size(part);
                if (expectedTotal > 0 && actual != expectedTotal) {
                    if (actual > expectedTotal) {
                        Files.deleteIfExists(part);
                        Files.deleteIfExists(partMeta);
                    }
                    throw new RetryableDownloadException("Неповне завантаження: " + actual + " з " + expectedTotal + " байт для " + SensitiveDataSanitizer.sanitizeUri(uri));
                }

                // Semantic validation failures are not resumable: discard poisoned bytes before retry/exit.
                String embeddedVersion;
                try {
                    embeddedVersion = validateCatalogArchive(part, uri);
                } catch (IOException invalid) {
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    throw invalid;
                }

                String sha256 = sha256(part);
                String etag = response.headers().firstValue("ETag").orElse("");
                String lastModified = response.headers().firstValue("Last-Modified").orElse("");
                AtomicFileSupport.moveReplacing(part, out);
                Files.deleteIfExists(partMeta);
                progress.accept(Math.min(1.0, progressStart + progressSpan));
                emitDownloadProgress(detailedProgress, actual, expectedTotal > 0 ? expectedTotal : actual, uri, progressStart, progressSpan);
                RemoteDownloadMetadata metadata = new RemoteDownloadMetadata(etag, lastModified, sha256, actual, "inpx");
                return new Downloaded(out, embeddedVersion, metadata);
            } catch (RetryableDownloadException e) {
                last = e;
                if (attempt == MAX_ATTEMPTS) break;
                backoff(attempt, cancel, e.retryAfter());
            } catch (IOException e) {
                last = e;
                if (cancel.get() || Thread.currentThread().isInterrupted()) throw e;
                if (isSemanticFailure(e)) {
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) break;
                backoff(attempt, cancel, null);
            }
        }
        throw OnlineRetryPolicy.safeNetworkFailure("Не вдалося завантажити каталог після " + MAX_ATTEMPTS + " спроб", uri, last);
    }

    private static void emitDownloadProgress(
            Consumer<RemoteDownloadProgress> listener, long processed, long total, URI uri,
            double progressStart, double progressSpan) {
        if (listener == null) return;
        double local = total > 0 ? Math.min(1.0, (double) processed / (double) total) : 0.0;
        double overall = Math.min(1.0, progressStart + local * progressSpan);
        try {
            listener.accept(new RemoteDownloadProgress(
                    Math.max(0, processed), total > 0 ? total : -1,
                    SensitiveDataSanitizer.sanitizeUri(uri), overall));
        } catch (RuntimeException ignored) {
            // Telemetry must never fail a download.
        }
    }

    private static String shortHash(String value) {
        String hash = Sha256Support.utf8(value);
        return hash.substring(0, Math.min(20, hash.length()));
    }

    private static String sha256(Path file) throws IOException {
        return Sha256Support.file(file);
    }

    /** Validate before any importer/database code sees the file. */
    private String validateCatalogArchive(Path file, URI source) throws IOException {
        if (Files.size(file) < 4) throw new IOException("Порожній або пошкоджений каталог: " + SensitiveDataSanitizer.sanitizeUri(source));
        byte[] prefix;
        try (InputStream in = Files.newInputStream(file)) { prefix = in.readNBytes(256); }
        String head = new String(prefix, StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            throw new IOException("Сервер повернув HTML замість INPX/ZIP: " + SensitiveDataSanitizer.sanitizeUri(source));
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            boolean hasInp = zip.stream().anyMatch(e -> !e.isDirectory() && e.getName().toLowerCase(Locale.ROOT).endsWith(".inp"));
            if (!hasInp) throw new IOException("Архів не містить жодного *.inp і не є каталогом MyHomeLib: " + SensitiveDataSanitizer.sanitizeUri(source));
            ZipEntry version = zip.getEntry("version.info");
            if (version == null) return null;
            try (InputStream in = zip.getInputStream(version)) {
                return normalizeVersion(new String(in.readNBytes(128), StandardCharsets.UTF_8));
            }
        } catch (ZipException e) {
            throw new IOException("Завантажений файл не є коректним INPX/ZIP: " + SensitiveDataSanitizer.sanitizeUri(source), e);
        }
    }

    private String fetchVersion(Collection collection, URI uri, AtomicBoolean cancel, boolean required) throws Exception {
        Exception last = null;
        final int attempts = 3;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (cancel.get() || Thread.currentThread().isInterrupted()) throw new IOException("Оновлення скасовано");

            HttpResponse<String> response;
            try (OnlineRequestLimiter.Permit ignored = requestLimiter.acquire(uri, cancel)) {
                response = client.send(request(collection, uri).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Оновлення скасовано", e);
            } catch (IOException e) {
                last = e;
                if (attempt < attempts) {
                    backoff(attempt, cancel, null);
                    continue;
                }
                break;
            }

            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                String version = normalizeVersion(response.body());
                if (version != null) return version;
                IOException invalid = new IOException("Некоректний version marker: " + SensitiveDataSanitizer.sanitizeUri(uri));
                if (required) throw invalid;
                log.warn("Optional Flibusta version marker is invalid: {}", SensitiveDataSanitizer.sanitizeUri(uri));
                return null;
            }

            IOException statusFailure = new IOException("HTTP " + status + " для " + SensitiveDataSanitizer.sanitizeUri(uri));
            if (!OnlineRetryPolicy.isRetryableStatus(status)) {
                if (required) throw statusFailure;
                log.warn("Optional Flibusta version marker is unavailable: {}",
                        SensitiveDataSanitizer.sanitizeText(statusFailure.getMessage()));
                return null;
            }

            last = statusFailure;
            if (attempt < attempts) {
                backoff(attempt, cancel, response.headers().firstValue("Retry-After").orElse(null));
            }
        }

        if (required) throw OnlineRetryPolicy.safeNetworkFailure("Не вдалося отримати версію каталогу Flibusta", uri, last);
        log.warn("Optional Flibusta version marker is unavailable: {}",
                SensitiveDataSanitizer.sanitizeText(last == null ? SensitiveDataSanitizer.sanitizeUri(uri) : last.getMessage()));
        return null;
    }


    private HttpRequest.Builder request(Collection collection, URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(httpPolicy.requestTimeout())
                .header("User-Agent", httpPolicy.userAgent())
                .header("Accept", "application/zip, application/octet-stream, text/plain;q=0.9, */*;q=0.1")
                .GET();
        if (collection != null && collection.getUser() != null && !collection.getUser().isBlank()) {
            String password = "";
            String decrypted = collection.getDecryptedPassword();
            if (decrypted != null) password = decrypted;
            String token = collection.getUser() + ":" + password;
            builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        }
        return builder;
    }

    private void backoff(int attempt, AtomicBoolean cancel, String retryAfter) throws IOException {
        long remaining = OnlineRetryPolicy.delayMillis(settings, attempt, retryAfter);
        while (remaining > 0) {
            if (cancel.get() || Thread.currentThread().isInterrupted()) throw new IOException("Оновлення скасовано");
            long slice = Math.min(100L, remaining);
            try { Thread.sleep(slice); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Оновлення скасовано", e); }
            remaining -= slice;
        }
    }

    private static boolean isSemanticFailure(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.contains("HTML замість") || m.contains("не є коректним INPX") || m.contains("не є каталогом MyHomeLib")
                || m.contains("Некоректний Content-Range") || m.contains("Порожній або пошкоджений");
    }

    private static URI requireHttpUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("URL INPX/сервера не задано");
        URI uri;
        try { uri = URI.create(value.trim()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Некоректний URL каталогу: " + value, e); }
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

    static MhlBases resolveMhlBases(URI uri) {
        if (uri == null || uri.getHost() == null || !uri.getHost().equalsIgnoreCase("alex80.github.io")) return null;
        String path = uri.getPath() == null ? "/" : uri.getPath();
        int cut = path.indexOf("/download/inpx");
        if (cut < 0) cut = path.indexOf("/update");
        String rootPath;
        if (cut >= 0) rootPath = path.substring(0, cut);
        else if (path.equals("/mhl") || path.equals("/mhl/")) rootPath = "/mhl";
        else return null;
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
    private static AtomicBoolean cancelFlag(AtomicBoolean cancel) { return cancel == null ? new AtomicBoolean(false) : cancel; }
    private static DoubleConsumer progressSink(DoubleConsumer progress) { return progress == null ? ignored -> { } : progress; }
    private static String normalizeVersion(String value) {
        if (value == null || value.isBlank()) return null;
        String firstLine = value.strip().lines().findFirst().orElse("").trim();
        String digits = firstLine.replaceAll("[^0-9]", "");
        return digits.length() < 8 ? null : digits.substring(0, 8);
    }
    private static long versionNumber(String value) {
        String normalized = normalizeVersion(value);
        if (normalized == null) return 0L;
        try { return Long.parseLong(normalized); } catch (NumberFormatException ignored) { return 0L; }
    }
    private static String maxVersion(String first, String second, String third, String... more) {
        String best = null;
        long bestValue = 0;
        String[] fixed = {first, second, third};
        for (String value : fixed) {
            long parsed = versionNumber(value);
            if (parsed > bestValue) { bestValue = parsed; best = normalizeVersion(value); }
        }
        if (more != null) {
            for (String value : more) {
                long parsed = versionNumber(value);
                if (parsed > bestValue) { bestValue = parsed; best = normalizeVersion(value); }
            }
        }
        return best;
    }
    private static String firstNonBlank(String first, String second) {
        String a = normalizeVersion(first); return a != null ? a : normalizeVersion(second);
    }
    private static void deleteQuietly(Path path) { if (path != null) try { Files.deleteIfExists(path); } catch (Exception ignored) { } }
    private static void closeQuietly(InputStream in) { if (in != null) try { in.close(); } catch (Exception ignored) { } }

    private record Downloaded(Path path, String version, RemoteDownloadMetadata metadata) { }
    record MhlBases(String inpxBase, String updateBase) { }
    private static final class RetryableDownloadException extends IOException {
        private final String retryAfter;
        RetryableDownloadException(String message) { this(message, null); }
        RetryableDownloadException(String message, String retryAfter) { super(message); this.retryAfter = retryAfter; }
        String retryAfter() { return retryAfter; }
    }
}
