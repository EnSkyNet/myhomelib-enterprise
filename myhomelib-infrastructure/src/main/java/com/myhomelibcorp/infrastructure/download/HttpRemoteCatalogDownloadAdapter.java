package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.catalog.CatalogSourceProfile;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogPackage;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.application.port.out.download.RemoteDownloadMetadata;
import com.myhomelibcorp.application.port.out.download.RemoteDownloadProgress;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.infrastructure.archive.SafeZipArchive;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.Sha256Support;
import com.myhomelibcorp.shared.util.BoundedHttpBodyHandlers;
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
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;

/** HTTP downloader for validated, resumable catalog updates. */
@Component
@Slf4j
public class HttpRemoteCatalogDownloadAdapter implements RemoteCatalogDownloadPort {
    private static final int MAX_VERSION_RESPONSE_BYTES = 4096;
    private static final CatalogSourceProfile FLIBUSTA = CatalogSourceProfile.FLIBUSTA_MHL;

    // Package-visible aliases kept for focused regression tests.
    static final String FLIBUSTA_FULL_INPX = FLIBUSTA.baselineFile();
    static final String FLIBUSTA_FULL_INFO = FLIBUSTA.fullVersionEndpoint();
    static final String FLIBUSTA_FULL_UPDATE = FLIBUSTA.fullUpdateFile();
    static final String FLIBUSTA_EXTRA_INFO = FLIBUSTA.incrementalVersionEndpoint();
    static final String FLIBUSTA_EXTRA_UPDATE = FLIBUSTA.incrementalUpdateFile();

    private static final int MAX_ATTEMPTS = 4;
    private static final long DEFAULT_MAX_DOWNLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long DEFAULT_MIN_FREE_SPACE_BYTES = 256L * 1024 * 1024;
    private static final long SPACE_RECHECK_BYTES = 16L * 1024 * 1024;

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
            // Використовуємо фабричний метод RemoteCatalogPackage.of
            return RemoteCatalogUpdatePlan.of(
                    List.of(RemoteCatalogPackage.of(downloaded.path(), sourceUri.toString(), downloaded.version(), full, downloaded.metadata())),
                    downloaded.version());
        }

        MhlBases mhl = resolveMhlBases(sourceUri);
        if (mhl == null) {
            throw new IllegalArgumentException(
                    "URL вказує не на INPX/ZIP-файл. Для MyHomeLib/Flibusta використовуйте " +
                            FLIBUSTA.baselineUrl() + " або прямий URL *.inpx/*.zip");
        }

        long current = versionNumber(currentVersion);
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

                if (current <= 0 || baselineNumber <= 0 || baselineNumber > current) {
                    // Використовуємо фабричний метод RemoteCatalogPackage.of
                    packages.add(RemoteCatalogPackage.of(
                            baseline.path(), baselineUri.toString(), baselineVersion, true, baseline.metadata()));
                } else {
                    removeDownloadedIfOwned(baseline.path(), baseline.metadata().sha256(),
                            "Unneeded catalog baseline cleanup", null);
                    sink.accept(1.0);
                    log.info("Remote Flibusta baseline is not newer: local={}, baseline={}",
                            currentVersion, baselineVersion);
                }
                return RemoteCatalogUpdatePlan.of(packages, maxVersion(currentVersion, baselineVersion, null));
            }

            long afterFull = current;
            if (current <= 0) {
                URI baselineUri = URI.create(mhl.inpxBase() + FLIBUSTA.baselineFile());
                Downloaded baseline = downloadOne(collection, baselineUri, flag, sink, detailSink, 0.0, 0.60);
                String baselineVersion = normalizeVersion(baseline.version());
                packages.add(RemoteCatalogPackage.of(baseline.path(), baselineUri.toString(), baselineVersion, true, baseline.metadata()));
                afterFull = versionNumber(baselineVersion);
                if (full > afterFull) {
                    URI fullUri = URI.create(mhl.updateBase() + FLIBUSTA.fullUpdateFile());
                    Downloaded downloaded = downloadOne(collection, fullUri, flag, sink, detailSink, 0.60, 0.25);
                    String version = firstNonBlank(fullVersion, downloaded.version());
                    packages.add(RemoteCatalogPackage.of(downloaded.path(), fullUri.toString(), version, true, downloaded.metadata()));
                    afterFull = versionNumber(version);
                }
            } else if (full > current) {
                URI fullUri = URI.create(mhl.updateBase() + FLIBUSTA.fullUpdateFile());
                Downloaded downloaded = downloadOne(collection, fullUri, flag, sink, detailSink, 0.0, extra > full ? 0.80 : 1.0);
                String version = firstNonBlank(fullVersion, downloaded.version());
                packages.add(RemoteCatalogPackage.of(downloaded.path(), fullUri.toString(), version, true, downloaded.metadata()));
                afterFull = versionNumber(version);
            }

            if (extra > Math.max(current, afterFull)) {
                URI extraUri = URI.create(mhl.updateBase() + FLIBUSTA.incrementalUpdateFile());
                double start = packages.isEmpty() ? 0.0 : (current <= 0 ? 0.85 : 0.80);
                Downloaded downloaded = downloadOne(collection, extraUri, flag, sink, detailSink, start, 1.0 - start);
                String version = firstNonBlank(extraVersion, downloaded.version());
                packages.add(RemoteCatalogPackage.of(downloaded.path(), extraUri.toString(), version, false, downloaded.metadata()));
            }

            String latest = maxVersion(currentVersion, fullVersion, extraVersion,
                    packages.stream().map(RemoteCatalogPackage::version).toArray(String[]::new));
            if (packages.isEmpty()) {
                sink.accept(1.0);
                log.info("Remote Flibusta catalog is up to date: local={}, full={}, extra={}", currentVersion, fullVersion, extraVersion);
            }
            return RemoteCatalogUpdatePlan.of(packages, latest);
        } catch (Exception e) {
            for (RemoteCatalogPackage pkg : packages) {
                removeDownloadedIfOwned(pkg.file(), pkg.metadata().sha256(),
                        "Failed catalog-update package cleanup", e);
            }
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
        SafeFilePublication.createDirectoriesNoLinks(dir, "Catalog download cache directory");
        String key = shortHash(uri.toString());
        Path part = dir.resolve("catalog-" + key + ".inpx.part");
        Path partMeta = dir.resolve("catalog-" + key + ".inpx.part.meta");
        Path out = dir.resolve("catalog-" + key + ".inpx");
        String expectedOutHash = SafeFilePublication.snapshotExistingTarget(out, "Catalog download target");

        OwnedDownloadFile.Snapshot partSnapshot = OwnedDownloadFile.snapshot(part, "Catalog partial download");
        HttpResumeSupport.ResumeMetadata resume = partSnapshot.size() > 0 ? HttpResumeSupport.read(partMeta, uri) : null;
        if (partSnapshot.size() > 0 && (resume == null || resume.validator().isBlank())) {
            preserveStaleDownloadState(part, "Untrusted catalog partial download");
            preserveStaleDownloadState(partMeta, "Untrusted catalog resume metadata");
            partSnapshot = OwnedDownloadFile.Snapshot.absent();
            resume = null;
        } else if (partSnapshot.exists() && partSnapshot.size() == 0L) {
            preserveStaleDownloadState(part, "Zero-length catalog partial download");
            partSnapshot = OwnedDownloadFile.Snapshot.absent();
        }
        if (!partSnapshot.exists() && Files.exists(partMeta, LinkOption.NOFOLLOW_LINKS)) {
            preserveStaleDownloadState(partMeta, "Orphaned catalog resume metadata");
            resume = null;
        }

        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (cancel.get() || Thread.currentThread().isInterrupted()) throw new IOException("Оновлення скасовано");
            long offset = partSnapshot.size();
            try (OnlineRequestLimiter.Permit ignored = requestLimiter.acquire(uri, cancel)) {
                HttpRequest.Builder builder = request(collection, uri);
                if (offset > 0) {
                    builder.header("Range", "bytes=" + offset + "-");
                    builder.header("If-Range", resume.validator());
                }
                HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                boolean resumed = offset > 0 && status == 206;
                if (resumed) {
                    try {
                        HttpResumeSupport.validateContentRange(response, offset, uri);
                    } catch (IOException invalidRange) {
                        closeQuietly(response.body());
                        removeOwnedCatalogPartial(part, partSnapshot, "Invalid Content-Range partial cleanup");
                        removeOwnedCatalogMeta(partMeta, resume, "Invalid Content-Range metadata cleanup");
                        partSnapshot = OwnedDownloadFile.Snapshot.absent();
                        resume = null;
                        throw invalidRange;
                    }
                } else if (status == 200) {
                    if (offset > 0) {
                        removeOwnedCatalogPartial(part, partSnapshot, "Restarted catalog partial cleanup");
                        removeOwnedCatalogMeta(partMeta, resume, "Restarted catalog metadata cleanup");
                        partSnapshot = OwnedDownloadFile.Snapshot.absent();
                        resume = null;
                        offset = 0L;
                    }
                } else if (status == 416 && offset > 0) {
                    closeQuietly(response.body());
                    removeOwnedCatalogPartial(part, partSnapshot, "HTTP 416 catalog partial cleanup");
                    removeOwnedCatalogMeta(partMeta, resume, "HTTP 416 catalog metadata cleanup");
                    partSnapshot = OwnedDownloadFile.Snapshot.absent();
                    resume = null;
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
                    removeOwnedCatalogPartial(part, partSnapshot, "HTML catalog partial cleanup");
                    removeOwnedCatalogMeta(partMeta, resume, "HTML catalog metadata cleanup");
                    partSnapshot = OwnedDownloadFile.Snapshot.absent();
                    resume = null;
                    throw new IOException("Сервер повернув HTML замість INPX/ZIP: " + SensitiveDataSanitizer.sanitizeUri(uri));
                }

                long responseLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                long maxDownloadBytes = maxDownloadBytes();
                long minFreeSpaceBytes = minFreeSpaceBytes();
                if (offset > maxDownloadBytes
                        || (responseLength >= 0 && responseLength > maxDownloadBytes - offset)) {
                    closeQuietly(response.body());
                    removeOwnedCatalogPartial(part, partSnapshot, "Oversized catalog partial cleanup");
                    removeOwnedCatalogMeta(partMeta, resume, "Oversized catalog metadata cleanup");
                    partSnapshot = OwnedDownloadFile.Snapshot.absent();
                    resume = null;
                    throw new NonRetryableCatalogDownloadException(downloadLimitMessage(maxDownloadBytes));
                }

                long expectedTotal = HttpResumeSupport.expectedTotal(response, offset);
                if (expectedTotal > maxDownloadBytes) {
                    closeQuietly(response.body());
                    removeOwnedCatalogPartial(part, partSnapshot, "Oversized catalog partial cleanup");
                    removeOwnedCatalogMeta(partMeta, resume, "Oversized catalog metadata cleanup");
                    partSnapshot = OwnedDownloadFile.Snapshot.absent();
                    resume = null;
                    throw new NonRetryableCatalogDownloadException(downloadLimitMessage(maxDownloadBytes));
                }
                try {
                    ensureDownloadSpace(dir, minFreeSpaceBytes, responseLength > 0 ? responseLength : 0L);
                } catch (NonRetryableCatalogDownloadException noSpace) {
                    closeQuietly(response.body());
                    removeOwnedCatalogPartial(part, partSnapshot, "No-space catalog partial cleanup");
                    removeOwnedCatalogMeta(partMeta, resume, "No-space catalog metadata cleanup");
                    partSnapshot = OwnedDownloadFile.Snapshot.absent();
                    resume = null;
                    throw noSpace;
                }

                resume = HttpResumeSupport.write(partMeta, uri, response,
                        resume == null ? null : resume.ownershipHash());
                long done = offset;
                long nextSpaceCheck = safeSpaceRecheckThreshold(done);
                OnlineProgressThrottle progressThrottle = new OnlineProgressThrottle(done);
                emitDownloadProgress(detailedProgress, done, expectedTotal, uri, progressStart, progressSpan);

                OwnedDownloadFile.Session session = OwnedDownloadFile.open(
                        part, partSnapshot, resumed, "Catalog partial download");
                Exception bodyFailure = null;
                try (InputStream in = response.body()) {
                    var os = session.outputStream();
                    byte[] buffer = new byte[128 * 1024];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        if (cancel.get() || Thread.currentThread().isInterrupted()) {
                            throw new IOException("Оновлення скасовано");
                        }
                        if (n == 0) continue;
                        if (done > maxDownloadBytes - n) {
                            throw new NonRetryableCatalogDownloadException(downloadLimitMessage(maxDownloadBytes));
                        }
                        os.write(buffer, 0, n);
                        done += n;
                        if (done >= nextSpaceCheck) {
                            ensureDownloadSpace(dir, minFreeSpaceBytes, 0L);
                            nextSpaceCheck = safeSpaceRecheckThreshold(done);
                        }
                        if (progressThrottle.shouldEmit(done, expectedTotal)) {
                            if (expectedTotal > 0) {
                                double local = Math.min(1.0, (double) done / expectedTotal);
                                progress.accept(Math.min(1.0, progressStart + local * progressSpan));
                            }
                            emitDownloadProgress(detailedProgress, done, expectedTotal, uri, progressStart, progressSpan);
                        }
                    }
                    os.flush();
                } catch (Exception failure) {
                    bodyFailure = failure;
                }
                try {
                    partSnapshot = session.finish();
                } catch (IOException ownershipFailure) {
                    if (bodyFailure != null) ownershipFailure.addSuppressed(bodyFailure);
                    throw new IOException("Catalog partial download was replaced while being written: " + part, ownershipFailure);
                } finally {
                    try { session.close(); } catch (IOException ignoredClose) { }
                }
                if (bodyFailure != null) throw bodyFailure;

                long actual = partSnapshot.size();
                if (expectedTotal > 0 && actual != expectedTotal) {
                    if (actual > expectedTotal) {
                        removeOwnedCatalogPartial(part, partSnapshot, "Oversized catalog partial cleanup");
                        removeOwnedCatalogMeta(partMeta, resume, "Oversized catalog metadata cleanup");
                        partSnapshot = OwnedDownloadFile.Snapshot.absent();
                        resume = null;
                    }
                    throw new RetryableDownloadException("Неповне завантаження: " + actual + " з " + expectedTotal
                            + " байт для " + SensitiveDataSanitizer.sanitizeUri(uri));
                }

                String embeddedVersion;
                try {
                    embeddedVersion = validateCatalogArchive(part, uri);
                    SafeFilePublication.requireOwnedPath(part, partSnapshot.sha256(), "Validated catalog partial");
                } catch (IOException invalid) {
                    removeOwnedCatalogPartial(part, partSnapshot, "Invalid catalog partial cleanup");
                    removeOwnedCatalogMeta(partMeta, resume, "Invalid catalog metadata cleanup");
                    partSnapshot = OwnedDownloadFile.Snapshot.absent();
                    resume = null;
                    throw invalid;
                }

                String sha256 = partSnapshot.sha256();
                String etag = response.headers().firstValue("ETag").orElse("");
                String lastModified = response.headers().firstValue("Last-Modified").orElse("");
                SafeFilePublication.publishReplacingIfUnchanged(
                        part, out, expectedOutHash, partSnapshot.sha256(), "Catalog download");
                partSnapshot = OwnedDownloadFile.Snapshot.absent();
                removeOwnedCatalogMeta(partMeta, resume, "Catalog resume metadata cleanup");
                resume = null;
                progress.accept(Math.min(1.0, progressStart + progressSpan));
                emitDownloadProgress(detailedProgress, actual, expectedTotal > 0 ? expectedTotal : actual,
                        uri, progressStart, progressSpan);
                RemoteDownloadMetadata metadata = RemoteDownloadMetadata.of(etag, lastModified, sha256, actual, "inpx");
                return new Downloaded(out, embeddedVersion, metadata);
            } catch (NonRetryableCatalogDownloadException e) {
                try { removeOwnedCatalogPartial(part, partSnapshot, "Non-retryable catalog partial cleanup"); }
                catch (IOException cleanupFailure) { e.addSuppressed(cleanupFailure); }
                try { removeOwnedCatalogMeta(partMeta, resume, "Non-retryable catalog metadata cleanup"); }
                catch (IOException cleanupFailure) { e.addSuppressed(cleanupFailure); }
                throw e;
            } catch (RetryableDownloadException e) {
                last = e;
                if (attempt == MAX_ATTEMPTS) break;
                backoff(attempt, cancel, e.retryAfter());
            } catch (IOException e) {
                last = e;
                if (cancel.get() || Thread.currentThread().isInterrupted()) throw e;
                if (isSemanticFailure(e)) {
                    try { removeOwnedCatalogPartial(part, partSnapshot, "Semantic-failure catalog partial cleanup"); }
                    catch (IOException cleanupFailure) { e.addSuppressed(cleanupFailure); }
                    try { removeOwnedCatalogMeta(partMeta, resume, "Semantic-failure catalog metadata cleanup"); }
                    catch (IOException cleanupFailure) { e.addSuppressed(cleanupFailure); }
                    throw e;
                }
                if (attempt == MAX_ATTEMPTS) break;
                backoff(attempt, cancel, null);
            }
        }
        throw OnlineRetryPolicy.safeNetworkFailure(
                "Не вдалося завантажити каталог після " + MAX_ATTEMPTS + " спроб", uri, last);
    }

    private static void removeOwnedCatalogPartial(
            Path part, OwnedDownloadFile.Snapshot snapshot, String role) throws IOException {
        if (snapshot != null && snapshot.exists()) {
            OwnedDownloadFile.removeIfOwned(part, snapshot, role);
        }
    }

    private static void removeOwnedCatalogMeta(
            Path partMeta, HttpResumeSupport.ResumeMetadata resume, String role) throws IOException {
        if (resume != null && resume.ownershipHash() != null && !resume.ownershipHash().isBlank()
                && Files.exists(partMeta, LinkOption.NOFOLLOW_LINKS)) {
            SafeFilePublication.removeIfOwned(partMeta, resume.ownershipHash(), role);
        }
    }

    private static void emitDownloadProgress(
            Consumer<RemoteDownloadProgress> listener, long processed, long total, URI uri,
            double progressStart, double progressSpan) {
        if (listener == null) return;
        double local = total > 0 ? Math.min(1.0, (double) processed / (double) total) : 0.0;
        double overall = Math.min(1.0, progressStart + local * progressSpan);
        try {
            // Використовуємо фабричний метод RemoteDownloadProgress.of
            listener.accept(RemoteDownloadProgress.of(
                    Math.max(0, processed), total > 0 ? total : -1,
                    SensitiveDataSanitizer.sanitizeUri(uri), overall));
        } catch (RuntimeException ignored) {
            // Telemetry must never fail a download.
        }
    }

    private static long safePartialSize(Path part) throws IOException {
        if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) return 0L;
        Path safe = SafeFilePublication.requireSafeRegularFile(part, "Catalog partial download");
        return Files.size(safe);
    }

    private static void preserveStaleDownloadState(Path path, String role) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        String hash = SafeFilePublication.sha256(path, role);
        Path evidence = SafeFilePublication.detachIfOwned(path, hash, role);
        log.warn("Preserved stale catalog download state as evidence: {}", evidence);
    }

    private static String shortHash(String value) {
        String hash = Sha256Support.utf8(value);
        return hash.substring(0, Math.min(20, hash.length()));
    }

    private static String sha256(Path file) throws IOException {
        return Sha256Support.file(file);
    }

    private String validateCatalogArchive(Path file, URI source) throws IOException {
        final String sanitizedSource = SensitiveDataSanitizer.sanitizeUri(source);
        try (SafeZipArchive opened = SafeZipArchive.open(file, StandardCharsets.UTF_8, "Downloaded catalog archive")) {
            if (opened.size() < 4) {
                throw new CatalogArchiveValidationException("Порожній або пошкоджений каталог: " + sanitizedSource);
            }
            String head = new String(opened.readPrefix(256), StandardCharsets.UTF_8)
                    .stripLeading()
                    .toLowerCase(Locale.ROOT);
            if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
                throw new CatalogArchiveValidationException("Сервер повернув HTML замість INPX/ZIP: " + sanitizedSource);
            }

            var zip = opened.zip();
            boolean hasInp = false;
            var entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".inp")) {
                    hasInp = true;
                    break;
                }
            }
            if (!hasInp) {
                throw new CatalogArchiveValidationException(
                        "Архів не містить жодного *.inp і не є каталогом MyHomeLib: " + sanitizedSource);
            }
            ZipArchiveEntry version = zip.getEntry("version.info");
            if (version == null) return null;
            try (InputStream in = zip.getInputStream(version)) {
                return normalizeVersion(new String(in.readNBytes(128), StandardCharsets.UTF_8));
            }
        } catch (CatalogArchiveValidationException validationFailure) {
            throw validationFailure;
        } catch (IOException archiveFailure) {
            throw new IOException("Завантажений файл не є коректним INPX/ZIP: " + sanitizedSource, archiveFailure);
        }
    }

    private static final class CatalogArchiveValidationException extends IOException {
        private CatalogArchiveValidationException(String message) {
            super(message);
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
                        BoundedHttpBodyHandlers.ofString(MAX_VERSION_RESPONSE_BYTES, StandardCharsets.UTF_8));
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
            CredentialTransportPolicy.requireHttpsWhenCredentialsPresent(uri, collection.getUser());
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
    private long maxDownloadBytes() {
        return positiveLongSetting("online.maxDownloadBytes", DEFAULT_MAX_DOWNLOAD_BYTES,
                1024L, 64L * 1024 * 1024 * 1024);
    }

    private long minFreeSpaceBytes() {
        return positiveLongSetting("online.minFreeSpaceBytes", DEFAULT_MIN_FREE_SPACE_BYTES,
                0L, 16L * 1024 * 1024 * 1024);
    }

    private long positiveLongSetting(String key, long fallback, long min, long max) {
        try {
            long value = Long.parseLong(settings.get(key, Long.toString(fallback)).trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void ensureDownloadSpace(Path directory, long minimumFreeBytes, long expectedAdditionalBytes)
            throws IOException {
        if (directory == null || minimumFreeBytes <= 0 && expectedAdditionalBytes <= 0) return;
        long usable = Files.getFileStore(directory).getUsableSpace();
        long required;
        try {
            required = Math.addExact(minimumFreeBytes, Math.max(0L, expectedAdditionalBytes));
        } catch (ArithmeticException overflow) {
            required = Long.MAX_VALUE;
        }
        if (usable < required) {
            throw new NonRetryableCatalogDownloadException(
                    "Недостатньо вільного місця для завантаження каталогу: доступно "
                            + usable + " байт, потрібно щонайменше " + required + " байт");
        }
    }

    private static long safeSpaceRecheckThreshold(long done) {
        try {
            return Math.addExact(done, SPACE_RECHECK_BYTES);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String downloadLimitMessage(long maxDownloadBytes) {
        return "Завантаження каталогу перевищує дозволений ліміт " + maxDownloadBytes + " байт";
    }

    private static void removeDownloadedIfOwned(Path path, String expectedSha256, String role, Throwable owner) {
        if (path == null || expectedSha256 == null || expectedSha256.isBlank()) return;
        try {
            SafeFilePublication.removeIfOwned(path, expectedSha256, role);
        } catch (Exception cleanupFailure) {
            if (owner != null) owner.addSuppressed(cleanupFailure);
            else log.warn("Could not safely clean owned catalog file {}: {}", path,
                    SensitiveDataSanitizer.sanitizeText(cleanupFailure.getMessage()));
        }
    }

    private static void closeQuietly(InputStream in) { if (in != null) try { in.close(); } catch (Exception ignored) { } }

    private record Downloaded(Path path, String version, RemoteDownloadMetadata metadata) { }
    record MhlBases(String inpxBase, String updateBase) { }
    private static final class NonRetryableCatalogDownloadException extends IOException {
        NonRetryableCatalogDownloadException(String message) { super(message); }
    }

    private static final class RetryableDownloadException extends IOException {
        private final String retryAfter;
        RetryableDownloadException(String message) { this(message, null); }
        RetryableDownloadException(String message, String retryAfter) { super(message); this.retryAfter = retryAfter; }
        String retryAfter() { return retryAfter; }
    }
}