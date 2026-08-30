package com.myhomelibcorp.infrastructure.download.scenario;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.download.DownloadPayloadValidator;
import com.myhomelibcorp.infrastructure.download.OnlineHttpPolicy;
import com.myhomelibcorp.infrastructure.download.OnlineRequestLimiter;
import com.myhomelibcorp.infrastructure.download.OnlineProgressThrottle;
import com.myhomelibcorp.infrastructure.download.OnlineRetryPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
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
        List<DownloadScenarioCommand> commands = DownloadScenarioParser.parse(script);
        if (commands.isEmpty()) throw new DownloadScenarioException("ConnectionScript порожній");
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
                switch (command.type()) {
                    case ADD -> fields.add(new FormField(
                            macros.expand(command.first(), state == null ? null : uriText(state.responseUri())),
                            macros.expand(command.second(), state == null ? null : uriText(state.responseUri()))));
                    case PAUSE -> pause(Long.parseLong(command.first()), cancel);
                    case GET -> {
                        URI uri = safeHttpUri(macros.expand(command.first(), state == null ? null : uriText(state.responseUri())));
                        state = send(client, "GET", uri, fields, responseFile, collection, cancel, progress);
                        checked = false;
                    }
                    case POST -> {
                        URI uri = safeHttpUri(macros.expand(command.first(), state == null ? null : uriText(state.responseUri())));
                        state = send(client, "POST", uri, fields, responseFile, collection, cancel, progress);
                        checked = false;
                    }
                    case REDIR -> {
                        if (state == null || !state.redirected()) {
                            throw new DownloadScenarioException("ConnectionScript REDIR: попередній request не мав redirect-result");
                        }
                    }
                    case CHECK -> {
                        if (state == null || state.payload() == null) {
                            throw new DownloadScenarioException("ConnectionScript CHECK: відсутній response payload");
                        }
                        validator.validate(state.payload(), target, book, archived);
                        checked = true;
                    }
                }
            }
            if (state == null || state.payload() == null) {
                throw new DownloadScenarioException("ConnectionScript не виконав GET/POST");
            }
            // Even legacy scripts without CHECK must never commit semantic-invalid content in v7.1.
            if (!checked) validator.validate(state.payload(), target, book, archived);
            return new Result(state.payload(), state.responseUri(), checked);
        } catch (Exception e) {
            Files.deleteIfExists(responseFile); // semantic-invalid script response is not resumable
            throw e;
        }
    }

    private ResponseState send(HttpClient client, String method, URI uri, List<FormField> fields, Path responseFile,
                               Collection collection, AtomicBoolean cancel, DoubleConsumer progress) throws Exception {
        int retries = method.equals("GET") ? clamp(settings.getInt("online.retryCount", 3), 0, 6) : 0;
        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            checkCancelled(cancel);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(httpPolicy.requestTimeout())
                    .header("User-Agent", httpPolicy.userAgent());
            addBasicAuth(request, collection);
            if (method.equals("POST")) {
                String boundary = "----MHL71" + UUID.randomUUID().toString().replace("-", "");
                byte[] body = multipart(fields, boundary);
                request.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                request.GET();
            }
            try (OnlineRequestLimiter.Permit ignored = requestLimiter.acquire(uri, cancel)) {
                HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                    try (InputStream ignoredBody = response.body()) { }
                    IOException failure = new IOException(statusMessage(status));
                    if (method.equals("GET") && OnlineRetryPolicy.isRetryableStatus(status) && attempt < retries) {
                        last = failure; backoff(attempt + 1, cancel, retryAfter); continue;
                    }
                    throw new NonRetryableRequestException(failure.getMessage());
                }
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                long done = 0;
                OnlineProgressThrottle progressThrottle = new OnlineProgressThrottle(0L);
                try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(responseFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int n; (n = in.read(buffer)) >= 0;) {
                        checkCancelled(cancel);
                        if (n == 0) continue;
                        out.write(buffer, 0, n); done += n;
                        if (total > 0 && progressThrottle.shouldEmit(done, total)) {
                            progress.accept(Math.min(0.99, (double) done / total));
                        }
                    }
                }
                return new ResponseState(responseFile, uri, response.uri());
            } catch (NonRetryableRequestException e) {
                throw new DownloadScenarioException(e.getMessage());
            } catch (IOException e) {
                last = e;
                if (attempt >= retries || Thread.currentThread().isInterrupted() || (cancel != null && cancel.get())) {
                    // Do not propagate HttpClient URI text: a script may legally place %PASS% in the URL.
                    throw new DownloadScenarioException("ConnectionScript network request failed ("
                            + e.getClass().getSimpleName() + ")");
                }
                backoff(attempt + 1, cancel, null);
            }
        }
        throw new DownloadScenarioException("ConnectionScript network request failed");
    }

    private void addBasicAuth(HttpRequest.Builder request, Collection collection) {
        if (collection.getUser() == null || collection.getUser().isBlank()) return;
        String secret = decryptPassword(collection);
        String token = Base64.getEncoder().encodeToString(
                (collection.getUser() + ":" + (secret == null ? "" : secret)).getBytes(StandardCharsets.UTF_8));
        request.header("Authorization", "Basic " + token);
    }

    private String decryptPassword(Collection collection) {
        try { return collection.getDecryptedPassword(); }
        catch (Exception e) { throw new SecurityException("Не вдалося дешифрувати credentials online-колекції"); }
    }

    private static byte[] multipart(List<FormField> fields, String boundary) throws DownloadScenarioException {
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
    }

    private static URI safeHttpUri(String raw) throws DownloadScenarioException {
        if (raw == null || raw.isBlank()) throw new DownloadScenarioException("ConnectionScript URL порожній");
        try {
            URI uri = URI.create(percentEncodeUnsafe(raw));
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new DownloadScenarioException("ConnectionScript дозволяє лише HTTP/HTTPS URL");
            }
            if (uri.getHost() == null) throw new DownloadScenarioException("ConnectionScript URL не містить host");
            return uri;
        } catch (IllegalArgumentException e) {
            throw new DownloadScenarioException("Некоректний ConnectionScript URL");
        }
    }

    /** Percent-encodes Unicode/unsafe characters while preserving RFC URI delimiters and existing %HH escapes. */
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
        // RFC 3986 unreserved + gen-delims + sub-delims + percent for already encoded values.
        return Character.isLetterOrDigit(c) || "-._~:/?#[]@!$&'()*+,;=%".indexOf(c) >= 0;
    }

    private static void pause(long millis, AtomicBoolean cancel) throws DownloadScenarioException {
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
        pause(OnlineRetryPolicy.delayMillis(settings, attempt, retryAfter), cancel);
    }
    private static String statusMessage(int status) {
        return switch (status) { case 401 -> "HTTP 401: потрібна авторизація"; case 403 -> "HTTP 403: доступ заборонено";
            case 404 -> "HTTP 404: payload не знайдено"; default -> "HTTP " + status + " у ConnectionScript"; };
    }
    private static void checkCancelled(AtomicBoolean cancel) throws DownloadScenarioException {
        if (cancel != null && cancel.get() || Thread.currentThread().isInterrupted()) throw new DownloadScenarioException("ConnectionScript скасовано");
    }
    private static String uriText(URI uri) { return uri == null ? "" : uri.toString(); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static final class NonRetryableRequestException extends IOException {
        private NonRetryableRequestException(String message) { super(message); }
    }

}
