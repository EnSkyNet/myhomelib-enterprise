package com.myhomelibcorp.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationUpdaterMainTest {
    @TempDir Path temp;

    @Test
    void appliesSignedCandidateAndRemovesBackupAfterHealthEvidence() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] oldBytes = "old".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "new-version".getBytes(StandardCharsets.UTF_8);
        Path install = temp.resolve("app.jar"); Files.write(install, oldBytes);
        Path staged = temp.resolve("candidate.jar"); Files.write(staged, newBytes);
        Path backup = temp.resolve("backup.jar"); Path marker = temp.resolve("health.ok");
        var plan = signedPlan(pair, staged, install, backup, marker, newBytes);
        Path planFile = temp.resolve("pending.properties"); Files.writeString(planFile, "x=y\n");
        String script = "printf ok > \"$" + ApplicationUpdaterMain.HEALTH_ENV + "\"; sleep 1";
        var options = new ApplicationUpdaterMain.Options(planFile, 0, "8.0.0", "8.0.0", "linux", "x64",
                List.of("sh", "-c", script), Duration.ofSeconds(2));

        var result = new ApplicationUpdaterMain().apply(plan, options, Map.of("test", pair.getPublic()));
        assertThat(result.installed()).isTrue();
        assertThat(Files.readAllBytes(install)).containsExactly(newBytes);
        assertThat(Files.exists(backup)).isFalse();
        assertThat(Files.exists(planFile)).isFalse();
    }

    @Test
    void rollsBackWhenReplacementDoesNotBecomeHealthy() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] oldBytes = "old".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "new-version".getBytes(StandardCharsets.UTF_8);
        Path install = temp.resolve("app.jar"); Files.write(install, oldBytes);
        Path staged = temp.resolve("candidate.jar"); Files.write(staged, newBytes);
        Path backup = temp.resolve("backup.jar"); Path marker = temp.resolve("health.ok");
        var plan = signedPlan(pair, staged, install, backup, marker, newBytes);
        Path planFile = temp.resolve("pending.properties"); Files.writeString(planFile, "x=y\n");
        var options = new ApplicationUpdaterMain.Options(planFile, 0, "8.0.0", "8.0.0", "linux", "x64",
                List.of("sh", "-c", "exit 0"), Duration.ofMillis(100));

        assertThatThrownBy(() -> new ApplicationUpdaterMain().apply(plan, options, Map.of("test", pair.getPublic())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("healthy");
        assertThat(Files.readAllBytes(install)).containsExactly(oldBytes);
        assertThat(Files.exists(temp.resolve("backup.jar.failed-new"))).isTrue();
    }


    @Test
    void staleFailedNewEvidenceDoesNotPreventRollbackOfKnownGoodInstallation() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] oldBytes = "old".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "new-version".getBytes(StandardCharsets.UTF_8);
        Path install = temp.resolve("app-stale.jar"); Files.write(install, oldBytes);
        Path staged = temp.resolve("candidate-stale.jar"); Files.write(staged, newBytes);
        Path backup = temp.resolve("backup-stale.jar"); Path marker = temp.resolve("health-stale.ok");
        Path staleFailed = temp.resolve("backup-stale.jar.failed-new");
        byte[] staleEvidence = "older-failed-update".getBytes(StandardCharsets.UTF_8);
        Files.write(staleFailed, staleEvidence);
        var plan = signedPlan(pair, staged, install, backup, marker, newBytes);
        Path planFile = temp.resolve("pending-stale.properties"); Files.writeString(planFile, "x=y\n");
        var options = new ApplicationUpdaterMain.Options(planFile, 0, "8.0.0", "8.0.0", "linux", "x64",
                List.of("sh", "-c", "exit 0"), Duration.ofMillis(100));

        assertThatThrownBy(() -> new ApplicationUpdaterMain().apply(plan, options, Map.of("test", pair.getPublic())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("healthy");
        assertThat(Files.readAllBytes(install)).containsExactly(oldBytes);
        assertThat(Files.readAllBytes(staleFailed)).containsExactly(staleEvidence);
        assertThat(Files.exists(backup)).isFalse();
    }

    @Test
    void rejectsTamperedSignatureBeforeFilesystemMutation() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] oldBytes = "old".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "new-version".getBytes(StandardCharsets.UTF_8);
        Path install = temp.resolve("app.jar"); Files.write(install, oldBytes);
        Path staged = temp.resolve("candidate.jar"); Files.write(staged, newBytes);
        var plan = signedPlan(other, staged, install, temp.resolve("backup.jar"), temp.resolve("health.ok"), newBytes);
        Path planFile = temp.resolve("pending.properties"); Files.writeString(planFile, "x=y\n");
        var options = new ApplicationUpdaterMain.Options(planFile, 0, "8.0.0", "8.0.0", "linux", "x64",
                List.of("sh", "-c", "exit 0"), Duration.ofMillis(100));

        assertThatThrownBy(() -> new ApplicationUpdaterMain().apply(plan, options, Map.of("test", pair.getPublic())))
                .isInstanceOf(SecurityException.class).hasMessageContaining("signature");
        assertThat(Files.readAllBytes(install)).containsExactly(oldBytes);
        assertThat(Files.exists(staged)).isTrue();
    }

    private static ApplicationUpdaterMain.Plan signedPlan(KeyPair pair, Path staged, Path install, Path backup, Path marker,
                                                           byte[] bytes) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var unsigned = new ApplicationUpdaterMain.Plan("myhomelib", "8.1.0", "STABLE", "linux", "x64",
                "https://updates.example.invalid/app.jar", bytes.length, sha, "AA==", "test", "8.0.0",
                staged, install, backup, marker);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate()); signature.update(unsigned.canonicalPayload());
        return new ApplicationUpdaterMain.Plan(unsigned.productId(), unsigned.version(), unsigned.channel(), unsigned.platform(), unsigned.arch(),
                unsigned.artifactUrl(), unsigned.artifactLength(), unsigned.sha256(), Base64.getEncoder().encodeToString(signature.sign()),
                unsigned.keyId(), unsigned.minimumUpdaterVersion(), unsigned.stagedArtifact(), unsigned.installationPath(), unsigned.backupPath(), unsigned.healthMarker());
    }
}
