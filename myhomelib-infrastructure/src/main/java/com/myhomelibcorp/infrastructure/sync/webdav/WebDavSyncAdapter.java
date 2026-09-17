package com.myhomelibcorp.infrastructure.sync.webdav;

import com.myhomelibcorp.shared.util.NetworkUris;
import com.myhomelibcorp.application.port.out.sync.SyncTransportPort;
import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.infrastructure.sync.folder.SyncBundleJsonCodec;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** WebDAV transport for encrypted immutable sync bundles. */
public final class WebDavSyncAdapter implements SyncTransportPort {
    static final String FINAL_SUFFIX = ".mhl-sync.enc";
    static final String PART_SUFFIX = ".mhl-sync.part";
    private static final String DAV_XML = "<?xml version=\"1.0\" encoding=\"utf-8\"?><propfind xmlns=\"DAV:\"><prop><getcontentlength/></prop></propfind>";

    private final URI folderUri;
    private final WebDavHttpClient http;
    private final SyncBundleJsonCodec codec;
    private final Supplier<WebDavSyncSecrets> secretsSupplier;
    private final int maxRetries;
    private final Duration retryDelay;
    private final WebDavPropfindParser propfindParser = new WebDavPropfindParser();

    public WebDavSyncAdapter(URI folderUri, WebDavHttpClient http, SyncBundleJsonCodec codec,
                             Supplier<WebDavSyncSecrets> secretsSupplier, int maxRetries, Duration retryDelay) {
        this.folderUri = normalizeFolderUri(folderUri);
        this.http = Objects.requireNonNull(http, "http");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.secretsSupplier = Objects.requireNonNull(secretsSupplier, "secretsSupplier");
        if (maxRetries < 0 || maxRetries > 10) throw new IllegalArgumentException("maxRetries must be 0..10");
        this.maxRetries = maxRetries;
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) throw new IllegalArgumentException("retryDelay must not be negative");
    }

    @Override
    public void push(ChangeSet changeSet) {
        Objects.requireNonNull(changeSet, "changeSet");
        WebDavSyncSecrets secrets = requiredSecrets();
        ensureCollection(secrets);
        AesGcmSyncPayloadEnvelope envelope = new AesGcmSyncPayloadEnvelope(secrets.syncKeyRing());
        byte[] protectedBytes = envelope.protect(codec.encode(changeSet));
        String base = remoteToken(changeSet.sourceDeviceId()) + "-" + changeSet.sequence() + "-" + remoteToken(changeSet.changeSetId());
        URI target = child(base + FINAL_SUFFIX);
        URI part = child("." + base + PART_SUFFIX);

        WebDavHttpClient.Response head = exchange("HEAD", target, auth(secrets), null, secrets, false);
        if (head.statusCode() == 200) {
            ChangeSet existing = fetchBundle(target, secrets);
            if (existing.equals(changeSet)) return;
            throw new IllegalStateException("Remote sync bundle exists with different contents");
        }
        if (head.statusCode() != 404) requireStatus(head, "HEAD", 200, 404);

        requireStatus(exchange("PUT", part, with(auth(secrets), "Content-Type", "application/octet-stream"),
                protectedBytes, secrets, true), "PUT", 200, 201, 204);

        Map<String, String> moveHeaders = with(auth(secrets), "Destination", target.toString(), "Overwrite", "F");
        WebDavHttpClient.Response move = exchange("MOVE", part, moveHeaders, null, secrets, true);
        if (move.statusCode() == 412) {
            ChangeSet existing = fetchBundle(target, secrets);
            if (existing.equals(changeSet)) return;
        }
        requireStatus(move, "MOVE", 201, 204);
    }

    @Override
    public List<ChangeSet> pull(Map<String, Long> lastSequenceByDevice) {
        WebDavSyncSecrets secrets = requiredSecrets();
        ensureCollection(secrets);
        Map<String, Long> cursor = lastSequenceByDevice == null ? Map.of() : Map.copyOf(lastSequenceByDevice);
        List<ChangeSet> result = new ArrayList<>();
        Map<String, ChangeSet> unique = new HashMap<>();
        for (URI uri : listRemoteBundles(secrets)) {
            ChangeSet set = fetchBundle(uri, secrets);
            String key = set.sourceDeviceId() + ":" + set.sequence();
            ChangeSet previous = unique.putIfAbsent(key, set);
            if (previous != null && !previous.changeSetId().equals(set.changeSetId())) {
                throw new IllegalStateException("Conflicting WebDAV sync bundles for cursor " + key);
            }
            if (set.sequence() > cursor.getOrDefault(set.sourceDeviceId(), 0L)) result.add(set);
        }
        result.sort(Comparator.comparing(ChangeSet::sourceDeviceId).thenComparingLong(ChangeSet::sequence));
        return List.copyOf(result);
    }


    /**
     * Re-encrypts every legacy/non-active remote bundle with the active key. The previous key must
     * remain in SecretStore until this method succeeds on every participating endpoint.
     *
     * @return number of bundles that were rewrapped
     */
    public int migrateEncryptionToActiveKey() {
        WebDavSyncSecrets secrets = requiredSecrets();
        if (!secrets.rotationInProgress()) return 0;
        ensureCollection(secrets);
        AesGcmSyncPayloadEnvelope envelope = new AesGcmSyncPayloadEnvelope(secrets.syncKeyRing());
        int migrated = 0;
        for (URI target : listRemoteBundles(secrets)) {
            WebDavHttpClient.Response get = exchange("GET", target, auth(secrets), null, secrets, true);
            requireStatus(get, "GET", 200);
            if (!envelope.needsRotation(get.body())) continue;
            byte[] rotated = envelope.rewrap(get.body());
            String fileName = target.getPath().substring(target.getPath().lastIndexOf('/') + 1);
            URI part = child("." + fileName + ".rotate.part");
            requireStatus(exchange("PUT", part, with(auth(secrets), "Content-Type", "application/octet-stream"),
                    rotated, secrets, true), "PUT", 200, 201, 204);
            Map<String, String> moveHeaders = with(auth(secrets), "Destination", target.toString(), "Overwrite", "T");
            requireStatus(exchange("MOVE", part, moveHeaders, null, secrets, true), "MOVE", 201, 204);
            migrated++;
        }
        return migrated;
    }

    private List<URI> listRemoteBundles(WebDavSyncSecrets secrets) {
        Map<String, String> headers = with(auth(secrets), "Depth", "1", "Content-Type", "application/xml; charset=utf-8");
        WebDavHttpClient.Response response = exchange("PROPFIND", folderUri, headers,
                DAV_XML.getBytes(StandardCharsets.UTF_8), secrets, true);
        requireStatus(response, "PROPFIND", 207);
        List<URI> result = new ArrayList<>();
        for (String href : propfindParser.hrefs(response.body())) {
            URI uri = resolveListedHref(href);
            if (uri.getPath().endsWith(FINAL_SUFFIX)) result.add(uri);
        }
        result.sort(Comparator.comparing(URI::toString));
        return List.copyOf(result);
    }

    private ChangeSet fetchBundle(URI uri, WebDavSyncSecrets secrets) {
        WebDavHttpClient.Response response = exchange("GET", uri, auth(secrets), null, secrets, true);
        requireStatus(response, "GET", 200);
        byte[] clear = new AesGcmSyncPayloadEnvelope(secrets.syncKeyRing()).unprotect(response.body());
        return codec.decode(clear);
    }

    private void ensureCollection(WebDavSyncSecrets secrets) {
        WebDavHttpClient.Response response = exchange("MKCOL", folderUri, auth(secrets), null, secrets, true);
        if (response.statusCode() == 201 || response.statusCode() == 405) return;
        requireStatus(response, "MKCOL", 201, 405);
    }

    private WebDavHttpClient.Response exchange(String method, URI uri, Map<String, String> headers, byte[] body,
                                               WebDavSyncSecrets secrets, boolean retryable) {
        int attempt = 0;
        while (true) {
            try {
                WebDavHttpClient.Response response = http.exchange(method, uri, headers, body);
                if (retryable && isTransient(response.statusCode()) && attempt < maxRetries) {
                    attempt++;
                    delay(attempt);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (!retryable || attempt >= maxRetries) throw new IllegalStateException("WebDAV " + method + " failed", e);
                attempt++;
                delay(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("WebDAV " + method + " interrupted", e);
            }
        }
    }

    private void delay(int attempt) {
        if (retryDelay.isZero()) return;
        try {
            Thread.sleep(Math.multiplyExact(retryDelay.toMillis(), attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WebDAV retry interrupted", e);
        }
    }

    private Map<String, String> auth(WebDavSyncSecrets secrets) {
        String token = Base64.getEncoder().encodeToString((secrets.username() + ":" + secrets.password())
                .getBytes(StandardCharsets.UTF_8));
        return Map.of("Authorization", "Basic " + token, "Accept", "application/octet-stream, application/xml");
    }

    private URI resolveListedHref(String href) {
        URI resolved = folderUri.resolve(href).normalize();
        if (!sameOrigin(folderUri, resolved)) throw new SecurityException("WebDAV response references another origin");
        if (!resolved.getPath().startsWith(folderUri.getPath())) {
            throw new SecurityException("WebDAV response escaped the configured sync folder");
        }
        return resolved;
    }

    private URI child(String name) {
        return folderUri.resolve(name);
    }

    private WebDavSyncSecrets requiredSecrets() {
        WebDavSyncSecrets secrets = secretsSupplier.get();
        if (secrets == null) throw new IllegalStateException("WebDAV credentials are unavailable");
        return secrets;
    }

    private static boolean isTransient(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static void requireStatus(WebDavHttpClient.Response response, String method, int... allowed) {
        for (int code : allowed) if (response.statusCode() == code) return;
        throw new IllegalStateException("Unexpected WebDAV " + method + " status: " + response.statusCode());
    }

    private static Map<String, String> with(Map<String, String> source, String... keyValues) {
        Map<String, String> result = new HashMap<>(source);
        for (int i = 0; i < keyValues.length; i += 2) result.put(keyValues[i], keyValues[i + 1]);
        return Map.copyOf(result);
    }

    private static URI normalizeFolderUri(URI uri) {
        Objects.requireNonNull(uri, "folderUri");
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || NetworkUris.isLoopbackHttp(uri))) {
            throw new IllegalArgumentException("WebDAV requires HTTPS; plain HTTP is allowed only for loopback tests");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("Credentials must not be embedded in WebDAV URL");
        URI normalized = uri.normalize();
        String text = normalized.toString();
        if (!text.endsWith("/")) text += "/";
        return URI.create(text);
    }


    private static boolean sameOrigin(URI a, URI b) {
        return Objects.equals(lower(a.getScheme()), lower(b.getScheme()))
                && Objects.equals(lower(a.getHost()), lower(b.getHost()))
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String lower(String value) { return value == null ? null : value.toLowerCase(java.util.Locale.ROOT); }

    private static String remoteToken(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
