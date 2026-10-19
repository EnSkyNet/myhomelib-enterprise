package com.myhomelibcorp.update;

import com.myhomelibcorp.application.update.ReleaseManifest;
import com.myhomelibcorp.application.update.UpdateChannel;
import com.myhomelibcorp.application.update.UpdatePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationUpdateInstallerTest {
    @TempDir Path temp;

    @Test
    void publishesReplacementOnlyAfterVerificationAndAcceptsHealthMarker() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] oldBytes = "old-version".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "new-version".getBytes(StandardCharsets.UTF_8);
        Path install = temp.resolve("app.bin"); Files.write(install, oldBytes);
        Path staged = temp.resolve("candidate.bin"); Files.write(staged, newBytes);
        UpdatePlan plan = plan(pair, staged, install, newBytes);
        var installer = new ApplicationUpdateInstaller(id -> Optional.of(pair.getPublic()));

        var result = installer.apply(plan, context(), launchProbe("healthy"), Duration.ofSeconds(10));
        Files.delete(plan.healthMarker());
        awaitProbeExit(result.launchedPid());
        assertThat(result.installed()).isTrue();
        assertThat(Files.readAllBytes(install)).containsExactly(newBytes);
        assertThat(Files.exists(plan.backupPath())).isFalse();
    }

    @Test
    void missingHealthMarkerRestoresPreviousInstallation() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] oldBytes = "old-version".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "new-version".getBytes(StandardCharsets.UTF_8);
        Path install = temp.resolve("app.bin"); Files.write(install, oldBytes);
        Path staged = temp.resolve("candidate.bin"); Files.write(staged, newBytes);
        UpdatePlan plan = plan(pair, staged, install, newBytes);
        var installer = new ApplicationUpdateInstaller(id -> Optional.of(pair.getPublic()));

        assertThatThrownBy(() -> installer.apply(plan, context(), launchProbe("unhealthy"), Duration.ofSeconds(5)))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("healthy");
        assertThat(Files.readAllBytes(install)).containsExactly(oldBytes);
    }

    @Test
    void tamperedStagedArtifactNeverTouchesInstallation() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] oldBytes = "old-version".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "new-version".getBytes(StandardCharsets.UTF_8);
        Path install = temp.resolve("app.bin"); Files.write(install, oldBytes);
        Path staged = temp.resolve("candidate.bin"); Files.write(staged, newBytes);
        UpdatePlan plan = plan(pair, staged, install, newBytes);
        Files.writeString(staged, "tampered", StandardCharsets.UTF_8);
        var installer = new ApplicationUpdateInstaller(id -> Optional.of(pair.getPublic()));

        assertThatThrownBy(() -> installer.apply(plan, context(), launchProbe("unhealthy"), Duration.ofSeconds(5)))
                .isInstanceOf(SecurityException.class);
        assertThat(Files.readAllBytes(install)).containsExactly(oldBytes);
    }

    private static List<String> launchProbe(String outcome) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path classes = Path.of(LaunchProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return List.of(java.toString(), "-cp", classes.toString(), LaunchProbe.class.getName(), outcome);
    }

    private static void awaitProbeExit(long pid) throws Exception {
        var process = ProcessHandle.of(pid);
        if (process.isEmpty()) return;
        try {
            process.get().onExit().get(10, TimeUnit.SECONDS);
        } finally {
            if (process.get().isAlive()) {
                process.get().destroyForcibly();
                process.get().onExit().get(10, TimeUnit.SECONDS);
            }
        }
    }

    /** Real child JVM; no shell or executable lookup through PATH is required. */
    public static final class LaunchProbe {
        public static void main(String[] args) throws Exception {
            if (!"healthy".equals(args[0])) return;
            Path marker = Path.of(System.getenv(ApplicationUpdateInstaller.HEALTH_MARKER_ENV));
            Files.writeString(marker, "ok", StandardCharsets.UTF_8);
            // Remain alive until the test acknowledges health evidence; self-bound on failures.
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(10);
        }
    }
    private UpdatePlan plan(KeyPair pair, Path staged, Path install, byte[] newBytes) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(newBytes));
        ReleaseManifest unsigned = new ReleaseManifest("myhomelib", "8.1.0", UpdateChannel.STABLE, "linux", "x64",
                URI.create("https://updates.example.invalid/app.bin"), newBytes.length, sha, "AA==", "key", "8.0.0", "notes");
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(pair.getPrivate()); signature.update(unsigned.canonicalSignedPayload());
        ReleaseManifest signed = new ReleaseManifest(unsigned.productId(), unsigned.version(), unsigned.channel(), unsigned.platform(), unsigned.arch(),
                unsigned.artifactUri(), unsigned.artifactLength(), unsigned.sha256(), Base64.getEncoder().encodeToString(signature.sign()),
                unsigned.keyId(), unsigned.minimumUpdaterVersion(), unsigned.releaseNotes());
        return new UpdatePlan(signed, staged, install, temp.resolve("backup.bin"), temp.resolve("health.ok"), false, Instant.now());
    }

    private static ApplicationUpdateInstaller.VerificationContext context() {
        return new ApplicationUpdateInstaller.VerificationContext("myhomelib", "8.0.0", "8.0.0", "linux", "x64");
    }
}
