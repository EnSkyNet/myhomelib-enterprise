package com.myhomelibcorp.application.kobo;

import java.time.Instant;
import java.util.stream.Stream;

/** Infrastructure boundary for device credentials and durable incremental Kobo sync state. */
public interface KoboSyncPort {
    KoboDeviceStatus status();
    KoboCredentialIssue enableOrRotate();
    void revoke();
    boolean authenticate(String presentedToken);

    KoboSyncBatch reconcileAndLoad(Stream<KoboBookProjection> currentLibrary,
                                   KoboSyncCursor after,
                                   int limit);

    /** Returns a stable monotonic version, incrementing it when the canonical progress revision changes. */
    long observeProgressVersion(String bookId, String canonicalRevision);

    default void recordSyncSuccess(Instant when) { }
    default void recordSyncFailure(Instant when, String safeErrorCode) { }
}
