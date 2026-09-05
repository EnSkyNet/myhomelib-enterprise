package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.application.port.out.download.OnlineBookDownloadPort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.download.scenario.ConnectionScriptExecutor;
import com.myhomelibcorp.infrastructure.download.scenario.DownloadScenarioCommand;
import com.myhomelibcorp.infrastructure.download.scenario.DownloadScenarioParser;
import com.myhomelibcorp.infrastructure.download.source.DownloadMode;
import com.myhomelibcorp.infrastructure.download.source.DownloadSourceResolver;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.shared.util.Sha256Support;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
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
    private final DownloadSourceResolver sourceResolver;
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
        this.sourceResolver = new DownloadSourceResolver();
        // Reuse a client so HTTP/2 connections, TCP/TLS sessions and proxy pools survive between books.
        this.client = httpPolicy.create(null);
    }

    public DownloadedBook download(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress) throws Exception {
        return download(book, collection, cancelFlag, progress, false);
    }

    @Override
    public DownloadedBook download(BookDto book, Collection collection, AtomicBoolean cancelFlag,
                                   DoubleConsumer progress, boolean forceRefresh) throws Exception {
        if (book == null) throw new IllegalArgumentException("Book is required");
        if (collection == null) throw new IllegalStateException("Колекцію не вибрано");
        if (collection.getId() == null || collection.getId().isBlank()) {
            throw new IllegalStateException("Колекція не має stable ID");
        }

        // Логування параметрів завантаження
        logDownloadStart(book, collection);

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
            String resolvedArchiveEntry = payloadValidator.validate(target, target, book, archived);
            return buildResult(book, root, relative, target, archived, resolvedArchiveEntry);
        }

        // Перевіряємо, чи інший запит уже завантажує цей самий файл
        CompletableFuture<Path> ownerFuture = new CompletableFuture<>();
        CompletableFuture<Path> existingFuture = inFlightTargets.putIfAbsent(target, ownerFuture);
        if (existingFuture != null) {
            Path ready = awaitSharedDownload(existingFuture, cancel);
            String resolvedArchiveEntry = payloadValidator.validate(ready, ready, book, archived);
            progressSink.accept(1.0);
            return buildResult(book, root, relative, ready, archived, resolvedArchiveEntry);
        }

        try {
            // ВИЗНАЧЕННЯ РЕЖИМУ ЗАВАНТАЖЕННЯ
            List<DownloadScenarioCommand> commands = null;
            if (collection.getConnectionScript() != null && !collection.getConnectionScript().isBlank()) {
                // A configured script is authoritative. Invalid commands must fail visibly instead of
                // silently falling back to another download mechanism.
                commands = DownloadScenarioParser.parse(collection.getConnectionScript());
                log.debug("Parsed {} commands from ConnectionScript", commands.size());
            }

            DownloadMode mode = sourceResolver.resolve(collection, commands);
            log.info("Download mode resolved: {} for book: {}", mode, book.getId());

            String resolvedArchiveEntry;
            if (mode == DownloadMode.CONNECTION_SCRIPT) {
                resolvedArchiveEntry = downloadViaConnectionScript(
                        book, collection, relative, target, root, archived, cancel, progressSink);
            } else {
                String baseUrl = effectiveBaseUrl(collection);
                if (baseUrl == null || baseUrl.isBlank()) {
                    throw new IllegalStateException("Для online-колекції не задано URL");
                }
                Collection effectiveCollection = collectionWithEffectiveUrl(collection, baseUrl);
                resolvedArchiveEntry = downloadPhysical(
                        book, effectiveCollection, baseUrl, relative, target, archived, cancel, progressSink);
            }

            ownerFuture.complete(target);
            return buildResult(book, root, relative, target, archived, resolvedArchiveEntry);
        } catch (Exception e) {
            ownerFuture.completeExceptionally(e);
            throw e;
        } finally {
            inFlightTargets.remove(target, ownerFuture);
        }
    }

    /**
     * Завантаження через прямий HTTP GET.
     */
    private String downloadPhysical(BookDto book,
                                  Collection collection,
                                  String baseUrl,
                                  String relative,
                                  Path target,
                                  boolean archived,
                                  AtomicBoolean cancel,
                                  DoubleConsumer progress) throws Exception {
        log.info("DIRECT DOWNLOAD START: bookId={}, target={}", book.getId(), target);
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        Path partMeta = target.resolveSibling(target.getFileName() + ".part.meta");

        URI uri = buildUri(baseUrl, relative, book);
        log.info("DIRECT DOWNLOAD URL: {}", SensitiveDataSanitizer.sanitizeUri(uri));

        int retries = clamp(settings.getInt("online.retryCount", 3), 0, 6);
        int attempts = retries + 1;
        Exception last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            checkCancelled(cancel);
            try (OnlineRequestLimiter.Permit ignored = requestLimiter.acquire(uri, cancel)) {
                long existing = Files.isRegularFile(part) ? Files.size(part) : 0L;
                HttpResumeSupport.ResumeMetadata resume = existing > 0 ? HttpResumeSupport.read(partMeta, uri) : null;
                if (existing > 0 && (resume == null || resume.validator().isBlank())) {
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    existing = 0L;
                    resume = null;
                }
                HttpResponse<InputStream> response = client.send(
                        buildRequest(uri, collection, existing, resume), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                String responseContentType = response.headers().firstValue("Content-Type").orElse("unknown");
                long responseContentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                log.info("HTTP RESPONSE: status={}, finalUrl={}, contentType={}, contentLength={}",
                        status, SensitiveDataSanitizer.sanitizeUri(response.uri()),
                        responseContentType, responseContentLength);

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
                    throw e;
                }

                checkCancelled(cancel);
                String resolvedArchiveEntry;
                try {
                    resolvedArchiveEntry = payloadValidator.validate(part, target, book, archived);
                    log.info("PAYLOAD VALIDATED: bookId={}, bytes={}, archived={}, resolvedArchiveEntry={}",
                            book.getId(), Files.size(part), archived, resolvedArchiveEntry);
                } catch (IOException semanticFailure) {
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(partMeta);
                    throw new NonRetryableHttpException(semanticFailure.getMessage());
                }

                AtomicFileSupport.moveReplacing(part, target);
                Files.deleteIfExists(partMeta);
                progress.accept(1.0);

                log.info("STORAGE COMMIT: target={}, replace=atomic-with-safe-fallback", target);
                log.info("DIRECT DOWNLOAD COMPLETE: file={}, size={}", target, Files.size(target));
                return resolvedArchiveEntry;
            } catch (DownloadCancelledException e) {
                throw e;
            } catch (NonRetryableHttpException e) {
                Files.deleteIfExists(part);
                Files.deleteIfExists(partMeta);
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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

    /**
     * Завантаження через ConnectionScript.
     */
    private String downloadViaConnectionScript(BookDto book, Collection collection, String relative, Path target,
                                             Path root, boolean archived, AtomicBoolean cancel,
                                             DoubleConsumer progress) throws Exception {
        log.info("SCRIPT DOWNLOAD START: bookId={}, target={}", book.getId(), target);
        ConnectionScriptExecutor executor = new ConnectionScriptExecutor(settings, payloadValidator, requestLimiter);
        String baseUrl = effectiveBaseUrl(collection);
        Collection scriptCollection = baseUrl == null || baseUrl.isBlank()
                ? collection
                : collectionWithEffectiveUrl(collection, baseUrl);
        ConnectionScriptExecutor.Result result = executor.execute(
                collection.getConnectionScript(), book, scriptCollection, root, relative, target, archived, cancel, progress);
        checkCancelled(cancel);
        String resolvedArchiveEntry = result.resolvedArchiveEntry();
        log.info("PAYLOAD VALIDATED: bookId={}, bytes={}, archived={}, mode=CONNECTION_SCRIPT, resolvedArchiveEntry={}",
                book.getId(), Files.size(result.payload()), archived, resolvedArchiveEntry);

        // ConnectionScriptExecutor validates the final response (CHECK or mandatory implicit check).
        // Commit exactly those validated bytes; do not rescan the same archive after the atomic move.
        AtomicFileSupport.moveReplacing(result.payload(), target);
        progress.accept(1.0);
        log.info("STORAGE COMMIT: target={}, replace=atomic-with-safe-fallback, mode=CONNECTION_SCRIPT", target);
        log.info("SCRIPT DOWNLOAD COMPLETE: file={}, size={}", target, Files.size(target));
        return resolvedArchiveEntry;
    }

    /**
     * Логування початку завантаження.
     */
    private void logDownloadStart(BookDto book, Collection collection) {
        String script = collection.getConnectionScript();
        List<DownloadScenarioCommand> commands = null;
        try {
            if (script != null && !script.isBlank()) {
                commands = DownloadScenarioParser.parse(script);
            }
        } catch (Exception e) {
            log.debug("Failed to parse ConnectionScript for logging: {}", e.getMessage());
        }

        boolean hasGet = commands != null && commands.stream().anyMatch(c -> c.type() == DownloadScenarioCommand.Type.GET);
        boolean hasPost = commands != null && commands.stream().anyMatch(c -> c.type() == DownloadScenarioCommand.Type.POST);

        log.info("""
                ONLINE DOWNLOAD PREPARATION:
                  bookId: {}
                  title: {}
                  collection: {} ({})
                  collectionUrl: {}
                  connectionScriptPresent: {}
                  scriptCommandCount: {}
                  scriptHasGet: {}
                  scriptHasPost: {}
                """,
                book.getId(),
                book.getTitle(),
                collection.getName(),
                collection.getId(),
                collection.getUrl() != null ? SensitiveDataSanitizer.sanitizeText(collection.getUrl()) : "<empty>",
                script != null && !script.isBlank(),
                commands != null ? commands.size() : 0,
                hasGet,
                hasPost);
    }

    /**
     * Legacy collection.info files sometimes keep the base URL as the first bare HTTP(S) line
     * of ConnectionScript. Delphi ignored that line as a command but the same URL was also
     * available as PROP_URL. Recover it here for older/migrated Java collections where URL
     * was not persisted, so %URL% expands exactly as the scenario expects.
     */
    private static String effectiveBaseUrl(Collection collection) {
        if (collection == null) return null;
        if (collection.getUrl() != null && !collection.getUrl().isBlank()) return collection.getUrl().trim();
        String script = collection.getConnectionScript();
        if (script == null || script.isBlank()) return null;
        for (String line : script.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String candidate = line.trim();
            if (candidate.isEmpty() || candidate.chars().anyMatch(Character::isWhitespace)) continue;
            try {
                URI uri = URI.create(candidate);
                String scheme = uri.getScheme();
                if (uri.getHost() != null && scheme != null
                        && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    return candidate;
                }
            } catch (IllegalArgumentException ignored) {
                // Not the legacy URL preamble; continue looking.
            }
        }
        return null;
    }

    private static Collection collectionWithEffectiveUrl(Collection collection, String baseUrl) {
        if (collection.getUrl() != null && !collection.getUrl().isBlank()) return collection;
        return new Collection(collection.getId(), collection.getName(), collection.getRootFolder(), collection.getDbFile(),
                collection.getType(), collection.getUser(), collection.getPassword(), baseUrl, collection.getNotes(),
                collection.getConnectionScript());
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

    private DownloadedBook buildResult(BookDto book, Path root, String relative, Path target, boolean archived,
                                       String resolvedArchiveEntry) {
        String normalized = relative.replace('\\', '/');
        if (archived) {
            String actualEntry = resolvedArchiveEntry == null || resolvedArchiveEntry.isBlank()
                    ? book.getArchiveEntry() : resolvedArchiveEntry;
            return new DownloadedBook(root, normalized, book.getFileName(), actualEntry, target);
        }
        int slash = normalized.lastIndexOf('/');
        String folder = slash < 0 ? "" : normalized.substring(0, slash);
        String fileName = slash < 0 ? normalized : normalized.substring(slash + 1);
        return new DownloadedBook(root, folder, fileName, "", target);
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
        try {
            return requireInsideRoot(root, root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize(), relative);
        } catch (InvalidPathException unmappable) {
            // Some Unix/JVM combinations still expose an ASCII native filename encoding. Remote
            // catalog paths are Unicode, so constructing a Path may fail before any HTTP request.
            // Keep the logical catalog name in DownloadedBook, but use a deterministic ASCII
            // physical fallback that preserves the extension and cannot collide trivially.
            String fallback = filesystemSafeFallbackRelative(relative);
            Path target = root.resolve(fallback.replace('/', java.io.File.separatorChar)).normalize();
            log.warn("Filesystem cannot represent remote path; using deterministic local fallback {}", fallback);
            return requireInsideRoot(root, target, relative);
        }
    }

    private static Path requireInsideRoot(Path root, Path target, String logicalRelative) {
        if (!target.startsWith(root)) throw new IllegalArgumentException("Небезпечний шлях: " + logicalRelative);
        return target;
    }

    static String filesystemSafeFallbackRelative(String relative) {
        if (relative == null || relative.isBlank()) return "download~" + Sha256Support.utf8("").substring(0, 32);
        String normalized = relative.replace('\\', '/');
        StringBuilder out = new StringBuilder(normalized.length() + 32);
        int start = 0;
        for (int i = 0; i <= normalized.length(); i++) {
            if (i < normalized.length() && normalized.charAt(i) != '/') continue;
            String segment = normalized.substring(start, i);
            if (!segment.isBlank()) {
                if (!out.isEmpty()) out.append('/');
                out.append(filesystemSafeFallbackSegment(segment));
            }
            start = i + 1;
        }
        if (out.isEmpty()) return "download~" + Sha256Support.utf8(normalized).substring(0, 32);
        return out.toString();
    }

    private static String filesystemSafeFallbackSegment(String segment) {
        boolean asciiSafe = !segment.isBlank() && !segment.equals(".") && !segment.equals("..");
        for (int i = 0; asciiSafe && i < segment.length(); i++) {
            char ch = segment.charAt(i);
            asciiSafe = ch < 128 && (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-');
        }
        if (asciiSafe) return segment;

        int dot = segment.lastIndexOf('.');
        String extension = "";
        if (dot > 0 && dot < segment.length() - 1) {
            String candidate = segment.substring(dot);
            boolean safeExtension = candidate.length() <= 16;
            for (int i = 1; safeExtension && i < candidate.length(); i++) {
                char ch = candidate.charAt(i);
                safeExtension = ch < 128 && Character.isLetterOrDigit(ch);
            }
            if (safeExtension) extension = candidate;
        }

        String baseSource = extension.isEmpty() ? segment : segment.substring(0, dot);
        StringBuilder base = new StringBuilder(Math.min(80, baseSource.length()));
        boolean pendingDash = false;
        for (int i = 0; i < baseSource.length() && base.length() < 80; i++) {
            char ch = baseSource.charAt(i);
            if (ch < 128 && (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-')) {
                if (pendingDash && !base.isEmpty()) base.append('-');
                pendingDash = false;
                base.append(ch);
            } else {
                pendingDash = !base.isEmpty();
            }
        }
        if (base.isEmpty()) base.append("download");
        String hash = Sha256Support.utf8(segment).substring(0, 32);
        return base + "~" + hash + extension;
    }

    private String cleanRelative(String value) {
        if (value == null) return "";
        String v = value.replace('\\', '/').trim();
        while (v.startsWith("/")) v = v.substring(1);
        if (v.matches("^[A-Za-z]:/.*") || v.contains("../") || v.equals("..")) {
            int slash = v.lastIndexOf('/');
            String fileName = slash < 0 ? v : v.substring(slash + 1);
            return fileName.equals(".") || fileName.equals("..") ? "" : fileName;
        }
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