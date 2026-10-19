package com.myhomelibcorp.organizer.journal;

import java.nio.file.Path;
import java.util.UUID;

public record OrganizationJournalItem(UUID operationId, UUID itemId, Path sourcePath, String sourceEntry, Path targetPath,
                                      String sourceSha256, String targetSha256, String action,
                                      OrganizationItemState state, Path backupPath,
                                      String optimizationProfile, long sizeBefore, long sizeAfter,
                                      long bytesSaved, double percentSaved, String optimizationSummaryJson,
                                      String targetStandard, String deviceProfile, String compressionBackend, String validationStatus,
                                      String errorCode, String errorMessage) {
    public OrganizationJournalItem {
        sourceEntry = safe(sourceEntry);
        sourceSha256 = safe(sourceSha256);
        targetSha256 = safe(targetSha256);
        action = safe(action);
        optimizationProfile = safe(optimizationProfile);
        optimizationSummaryJson = optimizationSummaryJson == null ? "{}" : optimizationSummaryJson;
        targetStandard = safe(targetStandard);
        deviceProfile = safe(deviceProfile);
        compressionBackend = safe(compressionBackend);
        validationStatus = safe(validationStatus);
        errorCode = safe(errorCode);
        errorMessage = safe(errorMessage);
        sizeBefore = Math.max(0L, sizeBefore);
        sizeAfter = Math.max(0L, sizeAfter);
        bytesSaved = Math.max(0L, bytesSaved);
        percentSaved = Math.max(0.0, percentSaved);
    }

    /** Compatibility constructor used by callers that do not yet have extended optimization audit fields. */
    public OrganizationJournalItem(UUID operationId, UUID itemId, Path sourcePath, String sourceEntry, Path targetPath,
                                   String sourceSha256, String targetSha256, String action,
                                   OrganizationItemState state, Path backupPath,
                                   String optimizationProfile, long sizeBefore, long sizeAfter,
                                   long bytesSaved, double percentSaved, String optimizationSummaryJson,
                                   String errorCode, String errorMessage) {
        this(operationId, itemId, sourcePath, sourceEntry, targetPath, sourceSha256, targetSha256, action, state,
                backupPath, optimizationProfile, sizeBefore, sizeAfter, bytesSaved, percentSaved,
                optimizationSummaryJson, "", "", "", "", errorCode, errorMessage);
    }

    /** Compatibility constructor for legacy/non-archive journal callers. */
    public OrganizationJournalItem(UUID operationId, UUID itemId, Path sourcePath, Path targetPath,
                                   String sourceSha256, String targetSha256, String action,
                                   OrganizationItemState state, Path backupPath,
                                   String optimizationProfile, long sizeBefore, long sizeAfter,
                                   long bytesSaved, double percentSaved, String optimizationSummaryJson,
                                   String errorCode, String errorMessage) {
        this(operationId,itemId,sourcePath,"",targetPath,sourceSha256,targetSha256,action,state,backupPath,
                optimizationProfile,sizeBefore,sizeAfter,bytesSaved,percentSaved,optimizationSummaryJson,
                "", "", "", "", errorCode,errorMessage);
    }

    /**
     * Preserve every immutable journal attribute when changing an item's outcome.
     * Centralizing this copy prevents future audit fields being silently lost in
     * individual Apply/Undo use cases. A known source checksum always wins over
     * a fallback checksum collected during a failed Apply.
     */
    public OrganizationJournalItem withOutcome(OrganizationItemState nextState,
                                               String fallbackSourceSha256,
                                               String nextErrorCode,
                                               String nextErrorMessage) {
        String sourceHash = sourceSha256.isBlank() ? safe(fallbackSourceSha256) : sourceSha256;
        return new OrganizationJournalItem(operationId, itemId, sourcePath, sourceEntry,
                targetPath, sourceHash, targetSha256, action, nextState, backupPath,
                optimizationProfile, sizeBefore, sizeAfter, bytesSaved, percentSaved,
                optimizationSummaryJson, targetStandard, deviceProfile, compressionBackend,
                validationStatus, nextErrorCode, nextErrorMessage);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
