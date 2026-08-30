package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.application.port.out.download.OnlineBookDownloadPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.myhomelibcorp.infrastructure.download.scenario.ConnectionScriptExecutor;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@Component
@Slf4j
public class HttpOnlineBookDownloadAdapter implements OnlineBookDownloadPort {
    private final ApplicationSettingsPort settings;
    private final ArchiveReader archiveReader;
    private final DownloadPayloadValidator payloadValidator;
    private final OnlineHttpPolicy httpPolicy;
    private final HttpClient client;
    private final OnlineRequestLimiter requestLimiter;

    /** One physical target may represent many books inside the same archive. */
    private final ConcurrentHashMap<Path, CompletableFuture<Path>> inFlightTargets = new ConcurrentHashMap<>();

    /** Backward-compatible constructor used by existing unit tests. */
    public HttpOnlineBookDownloadAdapter(ApplicationSettingsPort settings, ArchiveReader archiveReader) {
        this(settings, archiveReader, new DownloadPayloadValidator(archiveReader), new OnlineRequestLimiter(settings));
    }

    /** Compatibility constructor for focused tests. Spring uses the shared-limiter constructor below. */
    public HttpOnlineBookDownloadAdapter(ApplicationSettingsPort settings, ArchiveReader archiveReader,
                                         DownloadPayloadValidator payloadValidator) {
        this(settings, archiveReader, payloadValidator, new OnlineRequestLimiter(settings));
    }

    @Autowired
    public HttpOnlineBookDownloadAdapter(ApplicationSettingsPort settings, ArchiveReader archiveReader,
                                         DownloadPayloadValidator payloadValidator, OnlineRequestLimiter requestLimiter) {
        this.settings = settings;
        this.archiveReader = archiveReader;
        this.payloadValidator = payloadValidator;
        this.requestLimiter = requestLimiter;
        this.httpPolicy = new OnlineHttpPolicy(settings);
        // Reuse a client so HTTP/2 connections, TCP/TLS sessions and proxy pools survive between books.
        this.client = httpPolicy.create(null);
    }

    public DownloadedBook download(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress) throws Exception {
        return download(book, collection, cancelFlag, progress, false);
    }

    @Override
    public DownloadedBook download(BookDto book, Collection collection, AtomicBoolean cancelFlag,
                                   DoubleConsumer progress, boolean forceRefresh) throws Exception {
        String baseUrl = collection.getUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Для online-колекції не задано URL");
        }
        AtomicBoolean cancel = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        DoubleConsumer progressSink = progress == null ? ignored -> { } : progress;
        Path root = collection.getRootFolder() != null
                ? collection.getRootFolder().toAbsolutePath().normalize()
                : Path.of(System.getProperty("user.home"), ".myhomelibcorp", "downloads", collection.getId()).toAbsolutePath().normalize();
        Files.createDirectories(root);

        boolean archived = book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank();
        String relative = archived ? normalizeArchiveReference(book) : normalizeLooseReference(book);
        if (relative.isBlank()) throw new IllegalArgumentException("У книги відсутній шлях для завантаження");
        Path target = safeResolve(root, relative).toAbsolutePath().normalize();

        if (!forceRefresh && Files.isRegularFile(target) && Files.size(target) > 0) {
            payloadValidator.validate(target, target, book, archived);
            return buildResult(book, root, relative, target, archived);
        }

        CompletableFuture<Path> ownerFuture = new CompletableFuture<>();
        CompletableFuture<Path> existingFuture = inFlightTargets.putIfAbsent(target, ownerFuture);
        if (existingFuture != null) {
            Path ready = awaitSharedDownload(existingFuture, cancel);
            payloadValidator.validate(ready, ready, book, archived);
            progressSink.accept(1.0);
            return buildResult(book, root, relative, ready, archived);
        }

        try {
            if (collection.getConnectionScript() != null && !collection.getConnectionScript().isBlank()) {
                downloadViaConnectionScript(book, collection, relative, target, root, archived, cancel, progressSink);
            } else {
                downloadPhysical(book, collection, baseUrl, relative, target, archived, cancel, progressSink);
            }
            payloadValidator.validate(target, target, book, archived);
            ownerFuture.complete(target);
            return buildResult(book, root, relative, target, archived);
        } catch (Exception e) {
            ownerFuture.completeExceptionally(e);
            throw e;
        } finally {
            inFlightTargets.remove(target, ownerFuture);
        }
    }

    private void downloadPhysical(BookDto book,
                                  Collection collection,
                                  String baseUrl,
                                  String relative,
                                  Path target,
                                  boolean archived,
                                  AtomicBoolean cancel,
                                  DoubleConsumer progress) throws Exception {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Path partMeta = target.resolveSibling(target.getFileName() + ".part.meta");

        URI uri = buildUri(baseUrl, relative, book);
        int retries = clamp(settings.getInt("online.retryCount", 3), 0, 6);
        int attempts = retries + 1;
        Exception last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            checkCancelled(cancel);
            try (OnlineRequestLimiter.Permit ignored = requestLimiter.acquire(uri, cancel)) {
                long existing = Files.isRegularFile(part) ? Files.size(part) : 0L;
                HttpResumeSupport.ResumeMetadata resume = existing > 0 ? HttpResumeSupport.read(partMeta, uri) : null;
                if (existing > 0 && (resume == null || resume.validator().isBlank())) {
                    // A byte range without an entity validator can splice two remote versions together.
                    // Keep .part on cancellation, but only resume it when ETag/Last-Modified proves identity.
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    existing = 0L;
                    resume = null;
                }
                HttpResponse<InputStream> response = client.send(
                        buildRequest(uri, collection, existing, resume), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 416 && existing > 0) {
                    try (InputStream responseBody = response.body()) { }
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    if (attempt < attempts) continue;
                    throw new IOException("Сервер відхилив відновлення часткового завантаження (HTTP 416): "
                            + SensitiveDataSanitizer.sanitizeUri(uri));
                }
                if (status < 200 || status >= 300) {
                    try (InputStream responseBody = response.body()) { }
                    IOException statusError = httpStatusError(status, uri);
                    if (OnlineRetryPolicy.isRetryableStatus(status) && attempt < attempts) {
                        last = statusError;
                        sleepBackoff(attempt, response, cancel);
                        continue;
                    }
                    if (!OnlineRetryPolicy.isRetryableStatus(status)) throw new NonRetryableHttpException(statusError.getMessage());
                    throw statusError;
                }

                if (status == 206 && existing <= 0) {
                    try (InputStream responseBody = response.body()) { }
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    throw new NonRetryableHttpException("Сервер повернув часткову відповідь HTTP 206 без Range request: "
                            + SensitiveDataSanitizer.sanitizeUri(uri));
                }
                boolean resumed = status == 206 && existing > 0;
                if (resumed) {
                    try {
                        HttpResumeSupport.validateContentRange(response, existing, uri);
                    } catch (IOException invalidRange) {
                        try (InputStream responseBody = response.body()) { }
                        Files.deleteIfExists(part);
                        Files.deleteIfExists(partMeta);
                        throw invalidRange;
                    }
                } else if (existing > 0) {
                    // If-Range mismatch or server ignored Range: response is a complete new representation.
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    existing = 0L;
                }
                HttpResumeSupport.write(partMeta, uri, response);
                long responseLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                long total = responseLength > 0 ? existing + responseLength : -1L;
                long done = existing;
                StandardOpenOption[] outputOptions = resumed
                        ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                        : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(part, outputOptions)) {
                    int bufferKb = clamp(settings.getInt("online.downloadBufferKb", 256), 32, 1024);
                    byte[] buffer = new byte[bufferKb * 1024];
                    OnlineProgressThrottle progressThrottle = new OnlineProgressThrottle(done);
                    for (int n; (n = in.read(buffer)) >= 0;) {
                        checkCancelled(cancel);
                        if (n == 0) continue;
                        out.write(buffer, 0, n);
                        done += n;
                        if (total > 0 && progressThrottle.shouldEmit(done, total)) {
                            progress.accept(Math.min(1.0, (double) done / total));
                        }
                    }
                } catch (Exception e) {
                    // Keep a partial file for a future Range request on transient failures.
                    throw e;
                }

                checkCancelled(cancel);
                try {
                    payloadValidator.validate(part, target, book, archived);
                } catch (IOException semanticFailure) {
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    throw new NonRetryableHttpException(semanticFailure.getMessage());
                }
                AtomicFileSupport.moveReplacing(part, target);
                Files.deleteIfExists(partMeta);
                progress.accept(1.0);
                return;
            } catch (DownloadCancelledException e) {
                // A network .part is resumable. Keep it for an explicit future retry.
                throw e;
            } catch (NonRetryableHttpException e) {
                Files.deleteIfExists(part);
                Files.deleteIfExists(partMeta);
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Preserve resumable partial data on interruption/cancellation.
                throw new DownloadCancelledException();
            } catch (IOException e) {
                last = e;
                if (attempt >= attempts) {
                    throw OnlineRetryPolicy.safeNetworkFailure("Не вдалося завантажити книгу", uri, e);
                }
                log.warn("Тимчасова помилка завантаження (спроба {}/{}): {}", attempt, attempts,
                        SensitiveDataSanitizer.sanitizeText(e.getMessage()));
                sleepBackoff(attempt, null, cancel);
            }
        }

        throw OnlineRetryPolicy.safeNetworkFailure("Не вдалося завантажити книгу", uri, last);
    }



    private void downloadViaConnectionScript(BookDto book, Collection collection, String relative, Path target,
                                             Path root, boolean archived, AtomicBoolean cancel,
                                             DoubleConsumer progress) throws Exception {
        ConnectionScriptExecutor executor = new ConnectionScriptExecutor(settings, payloadValidator, requestLimiter);
        ConnectionScriptExecutor.Result result = executor.execute(
                collection.getConnectionScript(), book, collection, root, relative, target, archived, cancel, progress);
        checkCancelled(cancel);
        payloadValidator.validate(result.payload(), target, book, archived);
        AtomicFileSupport.moveReplacing(result.payload(), target);
        progress.accept(1.0);
    }

    private HttpRequest buildRequest(URI uri, Collection collection, long resumeFrom, HttpResumeSupport.ResumeMetadata resume) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(httpPolicy.requestTimeout())
                .header("User-Agent", httpPolicy.userAgent())
                .GET();

        String user = collection.getUser();
        String password = null;
        try {
            password = collection.getDecryptedPassword();
        } catch (Exception e) {
            throw new SecurityException("Не вдалося дешифрувати credentials online-колекції");
        }
        if (user != null && !user.isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + token);
        }
        if (resumeFrom > 0) {
            request.header("Range", "bytes=" + resumeFrom + "-");
            if (resume != null && !resume.validator().isBlank()) request.header("If-Range", resume.validator());
        }
        return request.build();
    }

    private Path awaitSharedDownload(CompletableFuture<Path> future, AtomicBoolean cancel) throws Exception {
        while (true) {
            checkCancelled(cancel);
            try {
                return future.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Periodically re-check caller cancellation while another request owns the download.
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                throw new IOException("Помилка спільного завантаження", cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownloadCancelledException();
            }
        }
    }

    private void sleepBackoff(int attempt, HttpResponse<?> response, AtomicBoolean cancel) throws DownloadCancelledException {
        String retryAfter = response == null ? null : response.headers().firstValue("Retry-After").orElse(null);
        long remaining = OnlineRetryPolicy.delayMillis(settings, attempt, retryAfter);
        while (remaining > 0) {
            checkCancelled(cancel);
            long slice = Math.min(250, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownloadCancelledException();
            }
            remaining -= slice;
        }
    }

    private IOException httpStatusError(int status, URI uri) {
        String safeUri = SensitiveDataSanitizer.sanitizeUri(uri);
        return switch (status) {
            case 401 -> new IOException("Потрібна авторизація (HTTP 401): " + safeUri);
            case 403 -> new IOException("Доступ заборонено (HTTP 403): " + safeUri);
            case 404 -> new IOException("Файл не знайдено на сервері (HTTP 404): " + safeUri);
            case 429 -> new IOException("Сервер тимчасово обмежив частоту запитів (HTTP 429): " + safeUri);
            default -> new IOException("HTTP " + status + " при завантаженні " + safeUri);
        };
    }

    private void checkCancelled(AtomicBoolean cancel) throws DownloadCancelledException {
        if (cancel.get() || Thread.currentThread().isInterrupted()) throw new DownloadCancelledException();
    }

    private DownloadedBook buildResult(BookDto book, Path root, String relative, Path target, boolean archived) {
        String normalized = relative.replace('\\', '/');
        if (archived) {
            return new DownloadedBook(root, normalized, book.getFileName(), book.getArchiveEntry(), target);
        }
        Path rel = Path.of(normalized);
        String folder = rel.getParent() == null ? "" : rel.getParent().toString().replace('\\', '/');
        return new DownloadedBook(root, folder, rel.getFileName().toString(), "", target);
    }

    private String normalizeArchiveReference(BookDto book) {
        String folder = cleanRelative(book.getFolder());
        if (isArchive(folder)) return folder;
        String file = cleanRelative(book.getFileName());
        if (isArchive(file)) return folder.isBlank() ? file : folder + "/" + file;
        return folder;
    }

    private String normalizeLooseReference(BookDto book) {
        String folder = cleanRelative(book.getFolder());
        String file = cleanRelative(book.getFileName());
        return folder.isBlank() ? file : folder + "/" + file;
    }

    private URI buildUri(String baseUrl, String relative, BookDto book) {
        String encodedPath = encodePath(relative);
        String template = baseUrl.trim();
        if (template.contains("{archive}") || template.contains("{file}") || template.contains("{entry}")) {
            String archive = isArchive(cleanRelative(book.getFolder())) ? cleanRelative(book.getFolder()) : relative;
            String value = template
                    .replace("{archive}", encodePath(archive))
                    .replace("{file}", encodePath(cleanRelative(book.getFileName())))
                    .replace("{entry}", encodePath(cleanRelative(book.getArchiveEntry())));
            return URI.create(value);
        }
        if (!template.endsWith("/")) template += "/";
        return URI.create(template + encodedPath);
    }

    private String encodePath(String path) {
        return String.join("/", java.util.Arrays.stream(path.replace('\\', '/').split("/"))
                .filter(s -> !s.isBlank())
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"))
                .toList());
    }

    private Path safeResolve(Path root, String relative) {
        Path target = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Небезпечний шлях: " + relative);
        return target;
    }

    private String cleanRelative(String value) {
        if (value == null) return "";
        String v = value.replace('\\', '/').trim();
        while (v.startsWith("/")) v = v.substring(1);
        if (v.matches("^[A-Za-z]:/.*") || v.contains("../") || v.equals("..")) return Path.of(v).getFileName().toString();
        return v;
    }

    private boolean isArchive(String value) {
        if (value == null) return false;
        String s = value.toLowerCase(Locale.ROOT);
        return s.endsWith(".zip") || s.endsWith(".fb2zip") || s.endsWith(".fb2.zip") || s.endsWith(".jar")
                || s.endsWith(".7z") || s.endsWith(".rar") || s.endsWith(".cbr") || s.endsWith(".cbz")
                || s.endsWith(".tar") || s.endsWith(".tar.gz") || s.endsWith(".tgz")
                || s.endsWith(".tar.bz2") || s.endsWith(".tbz2")
                || s.endsWith(".tar.xz") || s.endsWith(".txz") || s.endsWith(".cpio");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class NonRetryableHttpException extends IOException {
        NonRetryableHttpException(String message) { super(message); }
    }

    public static class DownloadCancelledException extends IOException {
        public DownloadCancelledException() { super("Завантаження скасовано"); }
    }
}
