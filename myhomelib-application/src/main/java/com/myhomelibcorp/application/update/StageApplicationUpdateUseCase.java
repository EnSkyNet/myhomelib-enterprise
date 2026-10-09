package com.myhomelibcorp.application.update;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

@Service
public final class StageApplicationUpdateUseCase {
    private final ApplicationUpdatePort port;
    private final UpdateVerificationService verification;
    private final LibraryOperationCoordinator operations;
    public StageApplicationUpdateUseCase(ApplicationUpdatePort port, UpdateVerificationService verification,
                                         LibraryOperationCoordinator operations) {
        this.port=Objects.requireNonNull(port); this.verification=Objects.requireNonNull(verification); this.operations=Objects.requireNonNull(operations);
    }
    public UpdatePlan execute(ReleaseManifest manifest, Path installationPath, Path stateDirectory, boolean portableMode) throws Exception {
        if (operations.isBusy()) throw new IllegalStateException("Application update is blocked while a library operation is active");
        Path staged=port.downloadVerifiedCandidateSource(manifest);
        verification.verifyArtifact(staged,manifest);
        Path state=stateDirectory.toAbsolutePath().normalize();
        UpdatePlan plan=new UpdatePlan(manifest,staged,installationPath,
                state.resolve("rollback-"+manifest.version()),state.resolve("health-"+manifest.version()+".ok"),portableMode, Instant.now());
        port.savePlan(plan);
        return plan;
    }
}
