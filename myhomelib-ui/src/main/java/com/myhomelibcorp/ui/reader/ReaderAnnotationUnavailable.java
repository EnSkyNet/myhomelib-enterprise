package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.annotation.AnnotationReaderItem;
import com.myhomelibcorp.application.annotation.AnnotationReaderResolver;

record ReaderAnnotationUnavailable(
        String id,
        ReaderAnnotationUnavailableReason reason,
        AnnotationReaderItem source,
        AnnotationReaderResolver.RebindCandidate rebindCandidate
) {
    ReaderAnnotationUnavailable {
        id = id == null ? "" : id.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("id is required");
        if (reason == null) reason = ReaderAnnotationUnavailableReason.UNRESOLVED;
    }

    boolean canOfferSafeRebind() {
        return reason == ReaderAnnotationUnavailableReason.ARTIFACT_MISMATCH
                && rebindCandidate != null
                && rebindCandidate.safeToRebind();
    }
}
