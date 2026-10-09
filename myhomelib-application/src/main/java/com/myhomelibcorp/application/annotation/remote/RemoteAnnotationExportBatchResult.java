package com.myhomelibcorp.application.annotation.remote;

import java.util.Set;

public record RemoteAnnotationExportBatchResult(Set<String> acceptedAnnotationIds) {
    public RemoteAnnotationExportBatchResult {
        acceptedAnnotationIds = acceptedAnnotationIds == null ? Set.of() : Set.copyOf(acceptedAnnotationIds);
    }
}
