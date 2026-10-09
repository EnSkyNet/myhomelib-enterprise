package com.myhomelibcorp.application.update;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StageApplicationUpdateUseCaseTest {
    @TempDir Path temp;

    @Test
    void refusesToStageWhileLibraryOperationIsActive() throws Exception {
        ApplicationUpdatePort port = mock(ApplicationUpdatePort.class);
        var service = new StageApplicationUpdateUseCase(port,
                new UpdateVerificationService(id -> Optional.empty()), new LibraryOperationCoordinator());
        var coordinator = new LibraryOperationCoordinator();
        service = new StageApplicationUpdateUseCase(port, new UpdateVerificationService(id -> Optional.empty()), coordinator);
        try (var lease = coordinator.acquire(LibraryOperationType.IMPORT)) {
            StageApplicationUpdateUseCase blocked = service;
            assertThatThrownBy(() -> blocked.execute(manifest("candidate".getBytes(StandardCharsets.UTF_8)),
                    temp.resolve("app.bin"), temp.resolve("state"), true))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("blocked");
            verifyNoInteractions(port);
        }
    }

    @Test
    void verifiesDownloadedBytesBeforePersistingPlan() throws Exception {
        byte[] bytes = "candidate".getBytes(StandardCharsets.UTF_8);
        Path staged = temp.resolve("candidate.bin");
        Files.write(staged, bytes);
        ReleaseManifest manifest = manifest(bytes);
        ApplicationUpdatePort port = mock(ApplicationUpdatePort.class);
        when(port.downloadVerifiedCandidateSource(manifest)).thenReturn(staged);
        var service = new StageApplicationUpdateUseCase(port,
                new UpdateVerificationService(id -> Optional.empty()), new LibraryOperationCoordinator());

        UpdatePlan plan = service.execute(manifest, temp.resolve("app.bin"), temp.resolve("state"), true);
        assertThat(plan.stagedArtifact()).isEqualTo(staged.toAbsolutePath().normalize());
        assertThat(plan.backupPath().getFileName().toString()).contains("rollback-8.1.0");
        assertThat(plan.portableMode()).isTrue();
        assertThat(plan.installationPath()).isEqualTo(temp.resolve("app.bin").toAbsolutePath().normalize());
        verify(port).savePlan(plan);
    }

    private static ReleaseManifest manifest(byte[] bytes) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new ReleaseManifest("myhomelib", "8.1.0", UpdateChannel.STABLE, "linux", "x64",
                URI.create("https://updates.example.invalid/app.bin"), bytes.length, sha,
                "AA==", "key", "8.0.0", "notes");
    }
}
