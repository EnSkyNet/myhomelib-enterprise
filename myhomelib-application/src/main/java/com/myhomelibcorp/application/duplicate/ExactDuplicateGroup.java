package com.myhomelibcorp.application.duplicate;

import java.util.List;

/** Artifacts whose complete byte streams have the same SHA-256 digest. */
public record ExactDuplicateGroup(String sha256, List<ExactDuplicateArtifact> artifacts) {
    public ExactDuplicateGroup {
        if (sha256 == null || sha256.isBlank()) throw new IllegalArgumentException("sha256 is required");
        artifacts = List.copyOf(artifacts == null ? List.of() : artifacts);
        if (artifacts.size() < 2) throw new IllegalArgumentException("Duplicate group must contain at least two artifacts");
    }
}
