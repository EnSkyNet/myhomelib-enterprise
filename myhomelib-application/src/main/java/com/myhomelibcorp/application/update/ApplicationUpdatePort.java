package com.myhomelibcorp.application.update;

import java.nio.file.Path;
import java.util.Optional;

public interface ApplicationUpdatePort {
    Optional<ReleaseManifest> fetchManifest(UpdateChannel channel) throws Exception;
    Path downloadVerifiedCandidateSource(ReleaseManifest manifest) throws Exception;
    void savePlan(UpdatePlan plan) throws Exception;
    Optional<UpdatePlan> pendingPlan() throws Exception;
    void clearPlan() throws Exception;
}
