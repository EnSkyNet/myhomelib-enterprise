package com.myhomelibcorp.application.update;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
public record UpdatePlan(ReleaseManifest manifest, Path stagedArtifact, Path installationPath,
                         Path backupPath, Path healthMarker, boolean portableMode, Instant createdAt) {
    public UpdatePlan {
        manifest = Objects.requireNonNull(manifest, "manifest");
        stagedArtifact = Objects.requireNonNull(stagedArtifact, "stagedArtifact").toAbsolutePath().normalize();
        installationPath = Objects.requireNonNull(installationPath, "installationPath").toAbsolutePath().normalize();
        backupPath = Objects.requireNonNull(backupPath, "backupPath").toAbsolutePath().normalize();
        healthMarker = Objects.requireNonNull(healthMarker, "healthMarker").toAbsolutePath().normalize();
        createdAt = createdAt == null ? Instant.now() : createdAt;
        if (stagedArtifact.equals(installationPath) || backupPath.equals(installationPath))
            throw new IllegalArgumentException("Update paths must be distinct");
    }
}
