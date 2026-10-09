package com.myhomelibcorp.infrastructure.integration.readwise;

import com.myhomelibcorp.application.annotation.remote.RemoteAnnotationExportBatchResult;
import com.myhomelibcorp.application.annotation.remote.RemoteAnnotationItem;
import com.myhomelibcorp.application.port.out.annotation.AnnotationRemoteExportPort;
import com.myhomelibcorp.application.port.out.annotation.AnnotationRemoteExportStatePort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.CollectionTransactionExecutor;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.myhomelibcorp.shared.security.SecretStore;
import com.myhomelibcorp.shared.security.SecretStoreContext;
import com.myhomelibcorp.shared.security.SecretStoreProvider;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/** Readwise v2 adapter. Token is kept only in SecretStore; export checkpoint contains hashes only. */
@Component
public final class ReadwiseAnnotationExportAdapter implements AnnotationRemoteExportPort, AnnotationRemoteExportStatePort {
    static final URI HIGHLIGHTS = URI.create("https://readwise.io/api/v2/highlights/");
    static final URI AUTH = URI.create("https://readwise.io/api/v2/auth/");
    static final String TOKEN_KEY = "integration.readwise.token";
    private static final int MAX_BATCH = 100;
    private static final int MAX_RESPONSE = 1_048_576;

    private final ObjectMapper mapper;
    private final QueryExecutor queryExecutor;
    private final CollectionManager collections;
    private final HttpClient http;
    private final LongConsumer sleeper;
    private final AtomicReference<Optional<SecretStore>> secretStore = new AtomicReference<>();

    @Autowired
    public ReadwiseAnnotationExportAdapter(ObjectMapper mapper, QueryExecutor queryExecutor, CollectionManager collections) {
        this(mapper, queryExecutor, collections, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build(), millis -> {
            try { Thread.sleep(millis); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Readwise retry interrupted", e); }
        }, null);
    }

    ReadwiseAnnotationExportAdapter(ObjectMapper mapper, QueryExecutor queryExecutor, CollectionManager collections,
                                    HttpClient http, LongConsumer sleeper, SecretStore testStore) {
        this.mapper = mapper; this.queryExecutor = queryExecutor; this.collections = collections;
        this.http = http; this.sleeper = sleeper;
        if (testStore != null) secretStore.set(Optional.of(testStore));
    }

    public boolean secureStoreAvailable() { return secrets().isPresent(); }
    public String secureStoreBackend() { return secrets().map(SecretStore::backendId).orElse(""); }
    public boolean configured() { return secrets().flatMap(s -> s.read(TOKEN_KEY)).filter(v -> !v.isBlank()).isPresent(); }
    public void configureToken(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Readwise token is required");
        secrets().orElseThrow(() -> new IllegalStateException("Secure secret store is unavailable")).write(TOKEN_KEY, token.trim());
    }
    public void clearToken() { secrets().ifPresent(s -> s.delete(TOKEN_KEY)); }

    @Override
    public void testConnection() {
        HttpResponse<byte[]> response = send(AUTH, "GET", null, false);
        if (response.statusCode() != 204) throw statusFailure(response.statusCode(), response.headers().firstValue("Retry-After").orElse(""));
    }

    @Override
    public RemoteAnnotationExportBatchResult exportBatch(List<RemoteAnnotationItem> items) {
        if (items == null || items.isEmpty()) return new RemoteAnnotationExportBatchResult(Set.of());
        if (items.size() > MAX_BATCH) throw new IllegalArgumentException("Readwise batch exceeds " + MAX_BATCH);
        ObjectNode root = mapper.createObjectNode();
        ArrayNode highlights = root.putArray("highlights");
        for (RemoteAnnotationItem item : items) {
            ObjectNode h = highlights.addObject();
            h.put("text", truncate(item.quote().isBlank() ? item.note() : item.quote(), 8191));
            h.put("title", truncate(item.bookTitle(), 511));
            h.put("author", truncate(item.authors(), 1024));
            h.put("source_type", "myhomelib");
            h.put("category", "books");
            if (!item.note().isBlank()) h.put("note", truncate(item.note(), 8191));
            h.put("location_type", "location");
            h.put("location", Math.max(0, (int)Math.round(item.position() * 1_000_000.0)));
            h.put("highlighted_at", item.highlightedAt().toString());
            h.put("highlight_url", "https://myhomelib.invalid/annotations/" + urlId(item.annotationId()));
        }
        byte[] body;
        try { body = mapper.writeValueAsBytes(root); }
        catch (Exception e) { throw new IllegalStateException("Readwise request encoding failed", e); }
        HttpResponse<byte[]> response = send(HIGHLIGHTS, "POST", body, true);
        if (response.statusCode() != 200) throw statusFailure(response.statusCode(), response.headers().firstValue("Retry-After").orElse(""));
        return new RemoteAnnotationExportBatchResult(items.stream().map(RemoteAnnotationItem::annotationId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Override
    public Optional<String> exportedFingerprint(String remote, String annotationId) {
        String value = queryExecutor.queryForObject("SELECT fingerprint FROM annotation_remote_export_state WHERE remote=? AND annotation_id=?",
                String.class, remote, annotationId);
        return Optional.ofNullable(value);
    }

    @Override
    public void markExported(String remote, Map<String, String> fingerprints) {
        if (fingerprints == null || fingerprints.isEmpty()) return;
        CollectionTransactionExecutor.execute(collections, () -> {
            List<Object[]> args = new ArrayList<>();
            String now = Instant.now().toString();
            fingerprints.forEach((id, fp) -> args.add(new Object[]{remote, id, fp, now}));
            queryExecutor.batchUpdate("""
                    INSERT INTO annotation_remote_export_state(remote,annotation_id,fingerprint,exported_at)
                    VALUES(?,?,?,?)
                    ON CONFLICT(remote,annotation_id) DO UPDATE SET fingerprint=excluded.fingerprint, exported_at=excluded.exported_at
                    """, args);
            return null;
        });
    }

    private HttpResponse<byte[]> send(URI uri, String method, byte[] body, boolean retryable) {
        String token = secrets().flatMap(s -> s.read(TOKEN_KEY)).filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalStateException("Readwise token is not configured"));
        int attempts = retryable ? 2 : 1;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45))
                        .header("Authorization", "Token " + token).header("Accept", "application/json");
                if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
                else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
                HttpResponse<byte[]> response = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.body() != null && response.body().length > MAX_RESPONSE) throw new SecurityException("Readwise response exceeds size limit");
                if (attempt < attempts && (response.statusCode() == 429 || response.statusCode() >= 500)) {
                    long delay = retryDelayMillis(response.headers().firstValue("Retry-After").orElse(""));
                    sleeper.accept(delay); continue;
                }
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Readwise request interrupted", e);
            } catch (java.io.IOException e) {
                last = new IllegalStateException("Readwise network request failed", e);
                if (attempt < attempts) { sleeper.accept(250); continue; }
            }
        }
        throw last == null ? new IllegalStateException("Readwise request failed") : last;
    }

    private Optional<SecretStore> secrets() {
        Optional<SecretStore> current = secretStore.get(); if (current != null) return current;
        SecretStoreContext context = new SecretStoreContext(AppPaths.configDir(), AppPaths.portableMode(),
                System.getProperty("os.name", ""), System.getProperty("user.name", "unknown"));
        Optional<SecretStore> opened = ServiceLoader.load(SecretStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get).sorted(Comparator.comparingInt(SecretStoreProvider::priority).reversed())
                .map(p -> { try { return p.open(context); } catch (RuntimeException ignored) { return Optional.<SecretStore>empty(); } })
                .flatMap(Optional::stream).findFirst();
        secretStore.compareAndSet(null, opened); return secretStore.get();
    }

    private static RuntimeException statusFailure(int code, String retryAfter) {
        if (code == 401 || code == 403) return new SecurityException("Readwise authentication failed");
        if (code == 429) return new IllegalStateException("Readwise rate limit exceeded" + (retryAfter.isBlank() ? "" : "; retry after " + retryAfter + "s"));
        return new IllegalStateException("Readwise HTTP " + code);
    }
    private static long retryDelayMillis(String value) {
        try { return Math.max(100, Math.min(5_000, Long.parseLong(value.trim()) * 1_000)); }
        catch (Exception ignored) { return 250; }
    }
    private static String truncate(String value, int max) { String v=value==null?"":value; return v.length()<=max?v:v.substring(0,max); }
    private static String urlId(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"); }
}
