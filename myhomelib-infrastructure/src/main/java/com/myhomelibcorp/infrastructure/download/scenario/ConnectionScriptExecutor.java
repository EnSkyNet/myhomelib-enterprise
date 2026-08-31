package com.myhomelibcorp.infrastructure.download.scenario;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.download.DownloadPayloadValidator;
import com.myhomelibcorp.infrastructure.download.OnlineHttpPolicy;
import com.myhomelibcorp.infrastructure.download.OnlineRequestLimiter;
import com.myhomelibcorp.infrastructure.download.OnlineProgressThrottle;
import com.myhomelibcorp.infrastructure.download.OnlineRetryPolicy;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

/** Executes the declarative MyHomeLib ConnectionScript without dynamic-code facilities. */
@Slf4j
public final class ConnectionScriptExecutor {
    public record Result(Path payload, URI responseUri, boolean checked) { }
    private record FormField(String name, String value) { }
    private record ResponseState(Path payload, URI requestedUri, URI responseUri) {
        boolean redirected() { return requestedUri != null && responseUri != null && !requestedUri.equals(responseUri); }
    }

    private final ApplicationSettingsPort settings;
    private final DownloadPayloadValidator validator;
    private final OnlineHttpPolicy httpPolicy;
    private final OnlineRequestLimiter requestLimiter;

    public ConnectionScriptExecutor(ApplicationSettingsPort settings, DownloadPayloadValidator validator) {
        this(settings, validator, new OnlineRequestLimiter(settings));
    }

    public ConnectionScriptExecutor(ApplicationSettingsPort settings, DownloadPayloadValidator validator,
                                    OnlineRequestLimiter requestLimiter) {
        this.settings = settings;
        this.validator = validator;
        this.requestLimiter = requestLimiter;
        this.httpPolicy = new OnlineHttpPolicy(settings);
    }

    public Result execute(String script, BookDto book, Collection collection, Path root, String relative,
                          Path target, boolean archived, AtomicBoolean cancel, DoubleConsumer progress) throws Exception {
        log.info("ConnectionScriptExecutor.execute() START for book: {}", book.getId());
        log.debug("  script length: {}", script != null ? script.length() : 0);
        log.debug("  target: {}", target);

        List<DownloadScenarioCommand> commands = DownloadScenarioParser.parse(script);
        log.info("  parsed {} commands", commands.size());
        for (int i = 0; i < commands.size(); i++) {
            log.debug("    command[{}]: type={}, first={}, second={}",
                    i, commands.get(i).type(), commands.get(i).first(), commands.get(i).second());
        }

        if (commands.isEmpty()) {
            log.warn("ConnectionScript is empty, will try fallback to Collection.url");
            throw new DownloadScenarioException("ConnectionScript порожній");
        }

        String password = decryptPassword(collection);
        DownloadMacroResolver macros = new DownloadMacroResolver(book, collection, root, relative, password);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = httpPolicy.create(cookies);
        List<FormField> fields = new ArrayList<>();
        Path responseFile = target.resolveSibling(target.getFileName() + ".script.part");
        ResponseState state = null;
        boolean checked = false;

        try {
            Files.createDirectories(responseFile.getParent());
            for (DownloadScenarioCommand command : commands) {
                checkCancelled(cancel);
                log.debug("Executing command: {}", command.type());
                switch (command.type()) {
                    case ADD -> {
                        String name = macros.expand(command.first(), state == null ? null : uriText(state.responseUri()));
                        String value = macros.expand(command.second(), state == null ? null : uriText(state.responseUri()));
                        fields.add(new FormField(name, value));
                        log.debug("  ADD: {}={}", name, value != null ? "[VALUE]" : "null");
                    }
                    case PAUSE -> {
                        long ms = Long.parseLong(command.first());
                        log.debug("  PAUSE: {} ms", ms);
                        pause(ms, cancel);
                    }
                    case GET -> {
                        String expanded = macros.expand(command.first(), state == null ? null : uriText(state.responseUri()));
                        URI uri = safeHttpUri(expanded);
                        log.info("  GET request: {}", uri);
                        state = send(client, "GET", uri, fields, responseFile, collection, cancel, progress);
                        checked = false;
                        log.info("  GET response: uri={}, payload={}", state.responseUri(), state.payload());
                    }
                    case POST -> {
                        String expanded = macros.expand(command.first(), state == null ? null : uriText(state.responseUri()));
                        URI uri = safeHttpUri(expanded);
                        log.info("  POST request: {}", uri);
                        state = send(client, "POST", uri, fields, responseFile, collection, cancel, progress);
                        checked = false;
                        log.info("  POST response: uri={}, payload={}", state.responseUri(), state.payload());
                    }
                    case REDIR -> {
                        log.debug("  REDIR");
                        if (state == null || !state.redirected()) {
                            throw new DownloadScenarioException("ConnectionScript REDIR: попередній request не мав redirect-result");
                        }
                        log.info("  REDIR from: {} to: {}", state.requestedUri(), state.responseUri());
                    }
                    case CHECK -> {
                        log.debug("  CHECK");
                        if (state == null || state.payload() == null) {
                            throw new DownloadScenarioException("ConnectionScript CHECK: відсутній response payload");
                        }
                        validator.validate(state.payload(), target, book, archived);
                        checked = true;
                        log.debug("  CHECK passed");
                    }
                }
            }

            log.info("All commands executed. state={}, checked={}", state != null ? "present" : "null", checked);

            if (state == null || state.payload() == null) {
                log.error("ConnectionScript did not execute GET/POST");
                throw new DownloadScenarioException("ConnectionScript не виконав GET/POST");
            }

            if (!checked) {
                log.debug("No CHECK command, validating payload anyway");
                validator.validate(state.payload(), target, book, archived);
            }

            log.info("ConnectionScript SUCCESS: payload={}, responseUri={}", state.payload(), state.responseUri());
            return new Result(state.payload(), state.responseUri(), checked);

        } catch (Exception e) {
            log.error("ConnectionScript FAILED: {}", e.getMessage(), e);
            Files.deleteIfExists(responseFile);
            throw e;
        }
    }

    private ResponseState send(HttpClient client, String method, URI uri, List<FormField> fields, Path responseFile,
                               Collection collection, AtomicBoolean cancel, DoubleConsumer progress) throws Exception {
        log.info("send() START: method={}, uri={}", method, uri);

        int retries = method.equals("GET") ? clamp(settings.getInt("online.retryCount", 3), 0, 6) : 0;
        Exception last = null;

        for (int attempt = 0; attempt <= retries; attempt++) {
            log.debug("send() attempt {}/{}", attempt + 1, retries + 1);
            checkCancelled(cancel);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(httpPolicy.requestTimeout())
                    .header("User-Agent", httpPolicy.userAgent());
            addBasicAuth(request, collection);

            if (method.equals("POST")) {
                // Для legacy MyHomeLib використовуємо application/x-www-form-urlencoded
                // Для сучасних API - multipart/form-data
                String contentType = detectContentType(fields);
                byte[] body = encodePostBody(fields, contentType);
                request.header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                log.debug("  POST body size: {} bytes, type: {}", body.length, contentType);
            } else {
                request.GET();
            }

            try (OnlineRequestLimiter.Permit ignored = requestLimiter.acquire(uri, cancel)) {
                long startTime = System.currentTimeMillis();
                HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                long duration = System.currentTimeMillis() - startTime;

                String contentType = response.headers().firstValue("Content-Type").orElse("unknown");
                long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                URI responseUri = response.uri();

                log.info("  HTTP {} in {} ms, Content-Type: {}, Content-Length: {}, Response URI: {}",
                        status, duration, contentType, contentLength, responseUri);

                if (status < 200 || status >= 300) {
                    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                    try (InputStream ignoredBody = response.body()) { }

                    // Якщо це redirect - зберігаємо для REDIR команди
                    if (status >= 300 && status < 400) {
                        String location = response.headers().firstValue("Location").orElse(null);
                        if (location != null) {
                            log.info("  Redirect to: {}", location);
                            return new ResponseState(null, uri, URI.create(location));
                        }
                    }

                    IOException failure = new IOException(statusMessage(status));
                    if (method.equals("GET") && OnlineRetryPolicy.isRetryableStatus(status) && attempt < retries) {
                        last = failure;
                        backoff(attempt + 1, cancel, retryAfter);
                        continue;
                    }
                    throw new NonRetryableRequestException(failure.getMessage());
                }

                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                long done = 0;
                OnlineProgressThrottle progressThrottle = new OnlineProgressThrottle(0L);

                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(responseFile,
                             StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        checkCancelled(cancel);
                        if (n == 0) continue;
                        out.write(buffer, 0, n);
                        done += n;
                        if (total > 0 && progressThrottle.shouldEmit(done, total)) {
                            progress.accept(Math.min(0.99, (double) done / total));
                        }
                    }
                }

                log.info("  Downloaded {} bytes", done);
                return new ResponseState(responseFile, uri, responseUri);

            } catch (NonRetryableRequestException e) {
                log.error("  Non-retryable error: {}", e.getMessage());
                throw new DownloadScenarioException(e.getMessage());
            } catch (IOException e) {
                log.warn("  IO error: {}", e.getMessage());
                last = e;
                if (attempt >= retries || Thread.currentThread().isInterrupted() || (cancel != null && cancel.get())) {
                    throw new DownloadScenarioException("ConnectionScript network request failed ("
                            + e.getClass().getSimpleName() + ")");
                }
                backoff(attempt + 1, cancel, null);
            }
        }
        throw new DownloadScenarioException("ConnectionScript network request failed");
    }

    private String detectContentType(List<FormField> fields) {
        // Якщо є файли або великі дані - використовуємо multipart
        long totalSize = fields.stream().mapToLong(f -> f.value().length()).sum();
        boolean hasBinary = fields.stream().anyMatch(f -> f.value().length() > 1024);
        if (hasBinary || totalSize > 4096) {
            return "multipart/form-data";
        }
        return "application/x-www-form-urlencoded";
    }

    private byte[] encodePostBody(List<FormField> fields, String contentType) throws DownloadScenarioException {
        if (contentType.contains("multipart/form-data")) {
            String boundary = "----MHL71" + UUID.randomUUID().toString().replace("-", "");
            StringBuilder b = new StringBuilder();
            int totalChars = 0;
            for (FormField f : fields) {
                if (f.name() == null || !f.name().matches("[A-Za-z0-9_.\\-]+"))
                    throw new DownloadScenarioException("Некоректне ім'я POST field");
                String value = f.value() == null ? "" : f.value();
                totalChars += value.length();
                if (totalChars > 1_000_000) throw new DownloadScenarioException("POST parameters перевищують safety limit");
                b.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"").append(f.name()).append("\"\r\n\r\n")
                        .append(value).append("\r\n");
            }
            b.append("--").append(boundary).append("--\r\n");
            return b.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            // application/x-www-form-urlencoded
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) b.append('&');
                FormField f = fields.get(i);
                b.append(URLEncoder.encode(f.name(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(f.value(), StandardCharsets.UTF_8));
            }
            return b.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private void addBasicAuth(HttpRequest.Builder request, Collection collection) {
        if (collection.getUser() == null || collection.getUser().isBlank()) return;
        String secret = decryptPassword(collection);
        String token = Base64.getEncoder().encodeToString(
                (collection.getUser() + ":" + (secret == null ? "" : secret)).getBytes(StandardCharsets.UTF_8));
        request.header("Authorization", "Basic " + token);
        log.debug("  Added Basic Auth for user: {}", collection.getUser());
    }

    private String decryptPassword(Collection collection) {
        try { return collection.getDecryptedPassword(); }
        catch (Exception e) { throw new SecurityException("Не вдалося дешифрувати credentials online-колекції"); }
    }

    private static URI safeHttpUri(String raw) throws DownloadScenarioException {
        if (raw == null || raw.isBlank()) {
            throw new DownloadScenarioException("ConnectionScript URL порожній");
        }
        try {
            URI uri = URI.create(percentEncodeUnsafe(raw));
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new DownloadScenarioException("ConnectionScript дозволяє лише HTTP/HTTPS URL");
            }
            if (uri.getHost() == null) {
                throw new DownloadScenarioException("ConnectionScript URL не містить host");
            }
            log.debug("  safe URI: {}", uri);
            return uri;
        } catch (IllegalArgumentException e) {
            throw new DownloadScenarioException("Некоректний ConnectionScript URL: " + raw);
        }
    }

    private static String percentEncodeUnsafe(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 32);
        for (int offset = 0; offset < raw.length();) {
            int cp = raw.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp >= 0x21 && cp <= 0x7E && isUriAscii((char) cp)) {
                out.append((char) cp);
                continue;
            }
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", b & 0xFF));
        }
        return out.toString();
    }

    private static boolean isUriAscii(char c) {
        return Character.isLetterOrDigit(c) || "-._~:/?#[]@!$&'()*+,;=%".indexOf(c) >= 0;
    }

    private static void pause(long millis, AtomicBoolean cancel) throws DownloadScenarioException {
        log.debug("pause() for {} ms", millis);
        long remaining = millis;
        while (remaining > 0) {
            checkCancelled(cancel);
            long slice = Math.min(100, remaining);
            try { Thread.sleep(slice); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new DownloadScenarioException("ConnectionScript скасовано"); }
            remaining -= slice;
        }
    }

    private void backoff(int attempt, AtomicBoolean cancel, String retryAfter) throws DownloadScenarioException {
        long delay = OnlineRetryPolicy.delayMillis(settings, attempt, retryAfter);
        log.debug("backoff() attempt={}, delay={}ms", attempt, delay);
        pause(delay, cancel);
    }

    private static String statusMessage(int status) {
        return switch (status) {
            case 401 -> "HTTP 401: потрібна авторизація";
            case 403 -> "HTTP 403: доступ заборонено";
            case 404 -> "HTTP 404: payload не знайдено";
            default -> "HTTP " + status + " у ConnectionScript";
        };
    }

    private static void checkCancelled(AtomicBoolean cancel) throws DownloadScenarioException {
        if (cancel != null && cancel.get() || Thread.currentThread().isInterrupted()) {
            throw new DownloadScenarioException("ConnectionScript скасовано");
        }
    }

    private static String uriText(URI uri) { return uri == null ? "" : uri.toString(); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static final class NonRetryableRequestException extends IOException {
        private NonRetryableRequestException(String message) { super(message); }
    }
}