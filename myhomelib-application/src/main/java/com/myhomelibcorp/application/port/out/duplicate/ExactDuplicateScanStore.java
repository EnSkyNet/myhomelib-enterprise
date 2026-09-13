package com.myhomelibcorp.application.port.out.duplicate;

import com.myhomelibcorp.application.duplicate.ArtifactScanCandidate;
import com.myhomelibcorp.application.duplicate.ExactDuplicateGroup;
import com.myhomelibcorp.application.duplicate.ExactDuplicateScanSession;

import java.util.List;

/** Persistence boundary for restart-safe, snapshot-based exact duplicate scans. */
public interface ExactDuplicateScanStore {
    ExactDuplicateScanSession resumeOrStart();
    List<ArtifactScanCandidate> nextBatch(String scanId, int limit);
    ExactDuplicateScanSession markHashed(String scanId, String artifactId, String sha256, long sizeBytes);
    ExactDuplicateScanSession markSkipped(String scanId, String artifactId, String reason);
    ExactDuplicateScanSession pause(String scanId);
    ExactDuplicateScanSession complete(String scanId);
    List<ExactDuplicateGroup> findDuplicateGroups();
}
