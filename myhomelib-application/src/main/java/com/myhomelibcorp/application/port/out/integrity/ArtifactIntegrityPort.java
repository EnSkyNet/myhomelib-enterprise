package com.myhomelibcorp.application.port.out.integrity;

import com.myhomelibcorp.application.integrity.ArtifactIntegrityReport;

/** Non-destructive, incremental local-artifact integrity audit boundary. */
public interface ArtifactIntegrityPort {
    /**
     * Audits every currently local/available artifact, reusing prior content hashes when the
     * physical file signature is unchanged. {@code detailLimit} bounds user-facing findings only.
     */
    ArtifactIntegrityReport auditIncremental(int detailLimit);
}
