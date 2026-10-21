package com.myhomelibcorp.infrastructure.update;

import com.myhomelibcorp.application.update.ReleaseManifest;
import com.myhomelibcorp.application.update.UpdateChannel;
import com.myhomelibcorp.application.update.UpdatePlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JdkApplicationUpdateAdapterTest {
    @TempDir Path temp;

    @AfterEach
    void clearProperties() {
        System.clearProperty("myhomelib.update.stableManifest");
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void parsesBoundedHttpsManifestAndRejectsChannelMismatch() throws Exception {
        System.setProperty("myhomelib.update.stableManifest", "https://updates.example.invalid/manifest.json");
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(manifestJson("STABLE").getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var adapter = new JdkApplicationUpdateAdapter(new ObjectMapper(), http);

        Optional<ReleaseManifest> found = adapter.fetchManifest(UpdateChannel.STABLE);
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().artifactUri()).hasScheme("https");

        when(response.body()).thenReturn(new ByteArrayInputStream(manifestJson("BETA").getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> adapter.fetchManifest(UpdateChannel.STABLE))
                .isInstanceOf(SecurityException.class).hasMessageContaining("channel");
    }

    @Test
    void downloadRejectsTruncationAndPublishesCompleteCandidate() throws Exception {
        System.setProperty("myhomelib.dataDir", temp.toString());
        byte[] bytes = "complete-update".getBytes(StandardCharsets.UTF_8);
        ReleaseManifest manifest = manifest(bytes);
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        var headers = java.net.http.HttpHeaders.of(java.util.Map.of(), (a,b) -> true);
        when(response.headers()).thenReturn(headers);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var adapter = new JdkApplicationUpdateAdapter(new ObjectMapper(), http);

        when(response.body()).thenReturn(new ByteArrayInputStream("short".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> adapter.downloadVerifiedCandidateSource(manifest))
                .isInstanceOf(SecurityException.class).hasMessageContaining("truncated");

        when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
        Path candidate = adapter.downloadVerifiedCandidateSource(manifest);
        assertThat(Files.readAllBytes(candidate)).containsExactly(bytes);
        assertThat(candidate.getFileName().toString()).contains(manifest.sha256());
    }

    @Test
    void damagedCachedCandidateDoesNotReplaceVerifiedNewDownload() throws Exception {
        System.setProperty("myhomelib.dataDir", temp.toString());
        byte[] bytes = "complete-update".getBytes(StandardCharsets.UTF_8);
        ReleaseManifest manifest = manifest(bytes);
        Path dir = temp.resolve("cache/application-updates/8.1.0");
        Files.createDirectories(dir);
        Path poisoned = dir.resolve("candidate-" + manifest.sha256() + ".bin");
        byte[] damaged = "x".repeat(bytes.length).getBytes(StandardCharsets.UTF_8);
        Files.write(poisoned, damaged);

        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a,b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        Path candidate = new JdkApplicationUpdateAdapter(new ObjectMapper(), http)
                .downloadVerifiedCandidateSource(manifest);
        assertThat(candidate).isNotEqualTo(poisoned);
        assertThat(Files.readAllBytes(candidate)).containsExactly(bytes);
        assertThat(Files.readAllBytes(poisoned)).containsExactly(damaged);
        try (var listing = Files.list(dir)) {
            assertThat(listing.filter(path -> path.getFileName().toString().endsWith(".part")).toList()).isEmpty();
        }
    }

    @Test
    void intactCachedCandidateIsReusedOnlyAfterDigestVerification() throws Exception {
        System.setProperty("myhomelib.dataDir", temp.toString());
        byte[] bytes = "complete-update".getBytes(StandardCharsets.UTF_8);
        ReleaseManifest manifest = manifest(bytes);
        Path dir = temp.resolve("cache/application-updates/8.1.0");
        Files.createDirectories(dir);
        Path cached = Files.write(dir.resolve("candidate-" + manifest.sha256() + ".bin"), bytes);

        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a,b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        Path result = new JdkApplicationUpdateAdapter(new ObjectMapper(), http)
                .downloadVerifiedCandidateSource(manifest);
        assertThat(result).isEqualTo(cached);
        assertThat(Files.readAllBytes(result)).containsExactly(bytes);
        try (var listing = Files.list(dir)) {
            assertThat(listing.toList()).containsExactly(cached);
        }
    }

    @Test
    void sameLengthCorruptedResponseIsRejectedBeforePublishingCandidate() throws Exception {
        System.setProperty("myhomelib.dataDir", temp.toString());
        byte[] expected = "complete-update".getBytes(StandardCharsets.UTF_8);
        ReleaseManifest manifest = manifest(expected);
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a,b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream("x".repeat(expected.length).getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        assertThatThrownBy(() -> new JdkApplicationUpdateAdapter(new ObjectMapper(), http)
                .downloadVerifiedCandidateSource(manifest))
                .isInstanceOf(SecurityException.class).hasMessageContaining("SHA-256 mismatch");
        Path dir = temp.resolve("cache/application-updates/8.1.0");
        try (var listing = Files.list(dir)) {
            assertThat(listing.toList()).isEmpty();
        }
    }

    @Test
    void interruptedArtifactDownloadRemovesPartialStagingFile() throws Exception {
        System.setProperty("myhomelib.dataDir", temp.toString());
        byte[] bytes = "complete-update".getBytes(StandardCharsets.UTF_8);
        ReleaseManifest manifest = manifest(bytes);
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a,b) -> true));
        InputStream interrupted = new InputStream() {
            int emitted;
            @Override public int read() throws java.io.IOException {
                if (emitted++ < 3) return 'x';
                throw new java.io.InterruptedIOException("simulated interrupted staging");
            }
        };
        when(response.body()).thenReturn(interrupted);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var adapter = new JdkApplicationUpdateAdapter(new ObjectMapper(), http);

        assertThatThrownBy(() -> adapter.downloadVerifiedCandidateSource(manifest))
                .isInstanceOf(java.io.InterruptedIOException.class);
        Path staging = temp.resolve("cache/application-updates/8.1.0");
        if (Files.isDirectory(staging)) {
            try (var files = Files.list(staging)) {
                assertThat(files.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".part")).toList()).isEmpty();
            }
        }
    }

    @Test
    void pendingPlanRejectsOversizedJournalWithoutLoadingItIntoMemory() throws Exception {
        System.setProperty("myhomelib.dataDir", temp.toString());
        Path journal = temp.resolve("updates/pending-update.properties");
        Files.createDirectories(journal.getParent());
        Files.write(journal, new byte[JdkApplicationUpdateAdapter.MAX_PENDING_PLAN_BYTES + 1]);
        var adapter = new JdkApplicationUpdateAdapter(new ObjectMapper(), mock(HttpClient.class));

        assertThatThrownBy(() -> adapter.pendingPlan())
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("size limit");
        // Rejection does not delete or alter the suspicious persisted journal.
        assertThat(Files.size(journal)).isEqualTo(JdkApplicationUpdateAdapter.MAX_PENDING_PLAN_BYTES + 1L);
    }

    @Test
    void updatePlanRoundTripsThroughAtomicJournal() throws Exception {
        System.setProperty("myhomelib.dataDir", temp.toString());
        byte[] bytes = "candidate".getBytes(StandardCharsets.UTF_8);
        ReleaseManifest manifest = manifest(bytes);
        Path staged = temp.resolve("stage.bin"); Files.write(staged, bytes);
        UpdatePlan plan = new UpdatePlan(manifest, staged, temp.resolve("install.bin"), temp.resolve("backup.bin"),
                temp.resolve("health.ok"), true, Instant.parse("2026-10-02T08:00:00Z"));
        var adapter = new JdkApplicationUpdateAdapter(new ObjectMapper(), mock(HttpClient.class));
        adapter.savePlan(plan);
        assertThat(adapter.pendingPlan()).contains(plan);
        adapter.clearPlan();
        assertThat(adapter.pendingPlan()).isEmpty();
    }

    @Test
    void bundledTrustStoreLoadsWithoutPrivateMaterial() {
        var trust = new BundledApplicationUpdateTrustAdapter();
        assertThat(trust.trustedKey("release-2026")).isPresent();
        assertThat(trust.trustedKey("missing")).isEmpty();
    }

    private static String manifestJson(String channel) throws Exception {
        byte[] bytes = "candidate".getBytes(StandardCharsets.UTF_8);
        return """
                {"productId":"myhomelib","version":"8.1.0","channel":"%s","platform":"linux","arch":"x64",
                 "artifactUrl":"https://updates.example.invalid/app.bin","artifactLength":%d,
                 "sha256":"%s","signature":"AA==","keyId":"release-2026","minimumUpdaterVersion":"8.0.0","releaseNotes":"notes"}
                """.formatted(channel, bytes.length, sha(bytes));
    }

    private static ReleaseManifest manifest(byte[] bytes) throws Exception {
        return new ReleaseManifest("myhomelib", "8.1.0", UpdateChannel.STABLE, "linux", "x64",
                URI.create("https://updates.example.invalid/app.bin"), bytes.length, sha(bytes), "AA==", "release-2026", "8.0.0", "notes");
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
