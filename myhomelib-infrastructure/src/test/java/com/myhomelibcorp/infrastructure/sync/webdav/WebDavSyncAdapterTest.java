package com.myhomelibcorp.infrastructure.sync.webdav;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.domain.model.sync.ChangeSet;
import com.myhomelibcorp.domain.model.sync.SyncEntityType;
import com.myhomelibcorp.domain.model.sync.SyncRecord;
import com.myhomelibcorp.domain.model.sync.SyncSchema;
import com.myhomelibcorp.infrastructure.sync.folder.SyncBundleJsonCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebDavSyncAdapterTest {
    private static final URI ENDPOINT = URI.create("https://dav.example/sync/");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final WebDavSyncSecrets SECRETS = new WebDavSyncSecrets("alice", "pw", KEY);

    @Test
    void twoDevicesConvergeThroughEncryptedRemoteBundles() {
        FakeWebDavHttpClient remote = new FakeWebDavHttpClient();
        WebDavSyncAdapter a = adapter(remote);
        WebDavSyncAdapter b = adapter(remote);
        ChangeSet fromA = changeSet("set-a", "device-a", 1, "private note from A");
        ChangeSet fromB = changeSet("set-b", "device-b", 1, "private note from B");

        a.push(fromA);
        b.push(fromB);

        assertThat(a.pull(Map.of())).containsExactly(fromA, fromB);
        assertThat(b.pull(Map.of("device-a", 1L))).containsExactly(fromB);
        assertThat(remote.files.keySet()).allMatch(uri -> !uri.getPath().endsWith(WebDavSyncAdapter.PART_SUFFIX));
        assertThat(remote.files.values()).allSatisfy(bytes -> {
            String raw = new String(bytes, StandardCharsets.ISO_8859_1);
            assertThat(raw).doesNotContain("private note");
        });
        assertThat(remote.lastAuthorization).startsWith("Basic ");
    }

    @Test
    void retriesInterruptedUploadWithoutPublishingPartialBundle() {
        FakeWebDavHttpClient remote = new FakeWebDavHttpClient();
        remote.failNextPut = true;
        WebDavSyncAdapter adapter = adapter(remote);
        ChangeSet set = changeSet("retry-set", "device-a", 1, "retry payload");

        adapter.push(set);

        assertThat(remote.putAttempts).isEqualTo(2);
        assertThat(adapter.pull(Map.of())).containsExactly(set);
        assertThat(remote.files.keySet()).allMatch(uri -> uri.getPath().endsWith(WebDavSyncAdapter.FINAL_SUFFIX));
    }

    @Test
    void migratesRemoteBundlesToRotatedKeyBeforePreviousKeyIsDropped() {
        FakeWebDavHttpClient remote = new FakeWebDavHttpClient();
        byte[] oldKeyBytes = new byte[32];
        java.util.Arrays.fill(oldKeyBytes, (byte) 1);
        byte[] newKeyBytes = new byte[32];
        java.util.Arrays.fill(newKeyBytes, (byte) 2);
        String oldKey = Base64.getEncoder().encodeToString(oldKeyBytes);
        String newKey = Base64.getEncoder().encodeToString(newKeyBytes);
        WebDavSyncSecrets oldSecrets = new WebDavSyncSecrets("alice", "pw", oldKey);
        WebDavSyncSecrets rotating = new WebDavSyncSecrets("alice", "pw", newKey, oldKey);
        ChangeSet set = changeSet("rotate-set", "device-a", 1, "private rotation payload");

        new WebDavSyncAdapter(ENDPOINT, remote, codec(), () -> oldSecrets, 1, Duration.ZERO).push(set);
        WebDavSyncAdapter rotatingAdapter = new WebDavSyncAdapter(ENDPOINT, remote, codec(), () -> rotating, 1, Duration.ZERO);

        assertThat(rotatingAdapter.pull(Map.of())).containsExactly(set);
        assertThat(rotatingAdapter.migrateEncryptionToActiveKey()).isEqualTo(1);

        WebDavSyncSecrets activeOnly = new WebDavSyncSecrets("alice", "pw", newKey);
        assertThat(new WebDavSyncAdapter(ENDPOINT, remote, codec(), () -> activeOnly, 1, Duration.ZERO).pull(Map.of()))
                .containsExactly(set);
        assertThatThrownBy(() -> new WebDavSyncAdapter(ENDPOINT, remote, codec(), () -> oldSecrets, 1, Duration.ZERO)
                .pull(Map.of())).isInstanceOf(SecurityException.class);
        assertThat(remote.files.keySet()).allMatch(uri -> !uri.getPath().contains(".rotate.part"));
    }

    @Test
    void rejectsCrossOriginHrefBeforeSendingCredentials() {
        FakeWebDavHttpClient remote = new FakeWebDavHttpClient();
        remote.extraHref = "https://evil.example/steal.mhl-sync.enc";
        WebDavSyncAdapter adapter = adapter(remote);

        assertThatThrownBy(() -> adapter.pull(Map.of()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("another origin");
        assertThat(remote.requestedHosts).doesNotContain("evil.example");
    }

    @Test
    void rejectsPlainHttpForNonLoopbackEndpoint() {
        assertThatThrownBy(() -> new WebDavSyncAdapter(URI.create("http://dav.example/sync/"),
                new FakeWebDavHttpClient(), codec(), () -> SECRETS, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    private static WebDavSyncAdapter adapter(FakeWebDavHttpClient remote) {
        return new WebDavSyncAdapter(ENDPOINT, remote, codec(), () -> SECRETS, 2, Duration.ZERO);
    }

    private static SyncBundleJsonCodec codec() {
        return new SyncBundleJsonCodec(new ObjectMapper());
    }

    private static ChangeSet changeSet(String id, String device, long sequence, String note) {
        Instant now = Instant.parse("2026-09-12T10:00:00Z").plusSeconds(sequence);
        SyncRecord record = SyncRecord.live("annotation:" + id, SyncEntityType.ANNOTATION, id,
                0, 1, now, device, Map.of("note", note));
        return new ChangeSet(id, device, sequence, "", now, SyncSchema.CURRENT_VERSION, List.of(record));
    }

    private static final class FakeWebDavHttpClient implements WebDavHttpClient {
        private final Map<URI, byte[]> files = new LinkedHashMap<>();
        private final List<String> requestedHosts = new ArrayList<>();
        private boolean collectionCreated;
        private boolean failNextPut;
        private int putAttempts;
        private String extraHref;
        private String lastAuthorization = "";

        @Override
        public Response exchange(String method, URI uri, Map<String, String> headers, byte[] body) throws IOException {
            requestedHosts.add(uri.getHost());
            lastAuthorization = headers.getOrDefault("Authorization", "");
            if (!lastAuthorization.startsWith("Basic ")) return response(401, new byte[0]);
            return switch (method) {
                case "MKCOL" -> {
                    if (collectionCreated) yield response(405, new byte[0]);
                    collectionCreated = true;
                    yield response(201, new byte[0]);
                }
                case "HEAD" -> response(files.containsKey(uri) ? 200 : 404, new byte[0]);
                case "PUT" -> {
                    putAttempts++;
                    if (failNextPut) {
                        failNextPut = false;
                        yield response(503, new byte[0]);
                    }
                    files.put(uri, body.clone());
                    yield response(201, new byte[0]);
                }
                case "MOVE" -> {
                    URI destination = URI.create(headers.get("Destination"));
                    boolean overwrite = "T".equalsIgnoreCase(headers.getOrDefault("Overwrite", "F"));
                    if (files.containsKey(destination) && !overwrite) yield response(412, new byte[0]);
                    byte[] data = files.remove(uri);
                    if (data == null) yield response(404, new byte[0]);
                    files.put(destination, data);
                    yield response(201, new byte[0]);
                }
                case "GET" -> files.containsKey(uri) ? response(200, files.get(uri)) : response(404, new byte[0]);
                case "PROPFIND" -> response(207, propfindBody());
                default -> response(405, new byte[0]);
            };
        }

        private byte[] propfindBody() {
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">");
            xml.append("<d:response><d:href>/sync/</d:href></d:response>");
            for (URI file : files.keySet()) {
                xml.append("<d:response><d:href>").append(file.getPath()).append("</d:href></d:response>");
            }
            if (extraHref != null) xml.append("<d:response><d:href>").append(extraHref).append("</d:href></d:response>");
            xml.append("</d:multistatus>");
            return xml.toString().getBytes(StandardCharsets.UTF_8);
        }

        private static Response response(int status, byte[] body) {
            return new Response(status, Map.of(), body);
        }
    }
}
