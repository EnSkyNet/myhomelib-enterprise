package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.application.port.out.download.OnlineBookDownloadPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
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

    /** One physical target may represent many books inside the same archive. */
    private final ConcurrentHashMap<Path, CompletableFuture<Path>> inFlightTargets = new ConcurrentHashMap<>();

    public HttpOnlineBookDownloadAdapter(ApplicationSettingsPort settings, ArchiveReader archiveReader) {
        this.settings = settings;
        this.archiveReader = archiveReader;
    }

    private HttpClient httpClient() {
        int connectSeconds = clamp(settings.getInt("online.connectTimeoutSeconds", 20), 2, 300);
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(connectSeconds))
                .build();
    }

    @Override
    public DownloadedBook download(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress) throws Exception {
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

        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            validateRequestedArchiveEntry(target, book, archived);
            return buildResult(book, root, relative, target, archived);
        }

        CompletableFuture<Path> ownerFuture = new CompletableFuture<>();
        CompletableFuture<Path> existingFuture = inFlightTargets.putIfAbsent(target, ownerFuture);
        if (existingFuture != null) {
            Path ready = awaitSharedDownload(existingFuture, cancel);
            validateRequestedArchiveEntry(ready, book, archived);
            progressSink.accept(1.0);
            return buildResult(book, root, relative, ready, archived);
        }

        try {
            downloadPhysical(book, collection, baseUrl, relative, target, cancel, progressSink);
            validateRequestedArchiveEntry(target, book, archived);
            ownerFuture.complete(target);
            return buildResult(book, root, relative, target, archived);
        } catch (Exception e) {
            ownerFuture.completeExceptionally(e);
            throw e;
        } finally {
            inFlightTargets.remove(target, ownerFuture);
        }
    }

    // Фрагмент з виправленням
    private void downloadPhysical(BookDto book,
                                  Collection collection,
                                  String baseUrl,
                                  String relative,
                                  Path target,
                                  AtomicBoolean cancel,
                                  DoubleConsumer progress) throws Exception {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");

        URI uri = buildUri(baseUrl, relative, book);
        int retries = clamp(settings.getInt("online.retryCount", 3), 0, 6);
        int attempts = retries + 1;
        Exception last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            checkCancelled(cancel);
            try {
                long existing = Files.isRegularFile(part) ? Files.size(part) : 0L;
                HttpResponse<InputStream> response = httpClient().send(
                        buildRequest(uri, collection, existing), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 416 && existing > 0) {
                    try (InputStream ignored = response.body()) { }
                    Files.deleteIfExists(part);
                    if (attempt < attempts) continue;
                    throw new IOException("Сервер відхилив відновлення часткового завантаження (HTTP 416): " + uri);
                }
                if (status < 200 || status >= 300) {
                    try (InputStream ignored = response.body()) { }
                    IOException statusError = httpStatusError(status, uri);
                    if (isTransientStatus(status) && attempt < attempts) {
                        last = statusError;
                        sleepBackoff(attempt, response, cancel);
                        continue;
                    }
                    if (!isTransientStatus(status)) throw new NonRetryableHttpException(statusError.getMessage());
                    throw statusError;
                }

                boolean resumed = status == 206 && existing > 0;
                if (!resumed && existing > 0) {
                    // Server ignored Range and returned a complete representation: restart safely.
                    Files.deleteIfExists(part);
                    existing = 0L;
                }
                long responseLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                long total = responseLength > 0 ? existing + responseLength : -1L;
                long done = existing;
                StandardOpenOption[] outputOptions = resumed
                        ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                        : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(part, outputOptions)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int n; (n = in.read(buffer)) >= 0;) {
                        checkCancelled(cancel);
                        if (n == 0) continue;
                        out.write(buffer, 0, n);
                        done += n;
                        if (total > 0) progress.accept(Math.min(1.0, (double) done / total));
                    }
                } catch (Exception e) {
                    // Keep a partial file for a future Range request on transient failures.
                    throw e;
                }

                checkCancelled(cancel);
                moveAtomically(part, target);
                progress.accept(1.0);
                return;
            } catch (DownloadCancelledException e) {
                // Explicit cancellation keeps the historical cleanup behavior.
                Files.deleteIfExists(part);
                throw e;
            } catch (NonRetryableHttpException e) {
                Files.deleteIfExists(part);
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Files.deleteIfExists(part);
                throw new DownloadCancelledException();
            } catch (IOException e) {
                last = e;
                if (attempt >= attempts) throw e;
                log.warn("Тимчасова помилка завантаження (спроба {}/{}): {}", attempt, attempts, e.getMessage());
                sleepBackoff(attempt, null, cancel);
            }
        }

        throw last != null ? last : new IOException("Не вдалося завантажити " + uri);
    }

    private HttpRequest buildRequest(URI uri, Collection collection, long resumeFrom) {
        int readSeconds = clamp(settings.getInt("online.readTimeoutSeconds", 120), 5, 7200);
        String userAgent = settings.get("online.userAgent", "MyHomeLib/1.0.0").trim();
        if (userAgent.isBlank()) userAgent = "MyHomeLib/1.0.0";
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(readSeconds))
                .header("User-Agent", userAgent)
                .GET();

        String user = collection.getUser();
        String password = null;
        try {
            password = collection.getDecryptedPassword();
        } catch (Exception e) {
            log.warn("Cannot decrypt collection password", e);
        }
        if (user != null && !user.isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (user + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
            request.header("Authorization", "Basic " + token);
        }
        if (resumeFrom > 0) request.header("Range", "bytes=" + resumeFrom + "-");
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

    private void validateRequestedArchiveEntry(Path target, BookDto book, boolean archived) throws IOException {
        if (!archived) return;
        String requested = normalizeEntry(book.getArchiveEntry());
        if (requested.isBlank()) return;
        boolean found = archiveReader.listEntries(target).stream()
                .map(HttpOnlineBookDownloadAdapter::normalizeEntry)
                .anyMatch(name -> name.equalsIgnoreCase(requested));
        if (!found) {
            throw new IOException("Завантажений архів не містить запис: " + book.getArchiveEntry());
        }
    }

    private void sleepBackoff(int attempt, HttpResponse<?> response, AtomicBoolean cancel) throws DownloadCancelledException {
        long millis = retryAfterMillis(response);
        if (millis <= 0) {
            long base = clamp(settings.getInt("online.retryBaseDelayMs", 750), 100, 10_000);
            millis = Math.min(30_000L, base * (1L << Math.min(5, Math.max(0, attempt - 1))));
        }
        long remaining = millis;
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

    private long retryAfterMillis(HttpResponse<?> response) {
        if (response == null) return -1;
        return response.headers().firstValue("Retry-After").map(value -> {
            try {
                return Math.min(30_000L, Math.max(0L, Long.parseLong(value.trim()) * 1000L));
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }).orElse(-1L);
    }

    private IOException httpStatusError(int status, URI uri) {
        return switch (status) {
            case 401 -> new IOException("Потрібна авторизація (HTTP 401): " + uri);
            case 403 -> new IOException("Доступ заборонено (HTTP 403): " + uri);
            case 404 -> new IOException("Файл не знайдено на сервері (HTTP 404): " + uri);
            case 429 -> new IOException("Сервер тимчасово обмежив частоту запитів (HTTP 429): " + uri);
            default -> new IOException("HTTP " + status + " при завантаженні " + uri);
        };
    }

    private boolean isTransientStatus(int status) {
        return status == 429 || status == 408 || status >= 500;
    }

    private void checkCancelled(AtomicBoolean cancel) throws DownloadCancelledException {
        if (cancel.get() || Thread.currentThread().isInterrupted()) throw new DownloadCancelledException();
    }

    private void moveAtomically(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
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

    private static String normalizeEntry(String value) {
        if (value == null) return "";
        String result = value.replace('\\', '/').trim();
        while (result.startsWith("/")) result = result.substring(1);
        return result;
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
