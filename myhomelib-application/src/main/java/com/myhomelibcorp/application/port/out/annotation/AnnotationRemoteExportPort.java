package com.myhomelibcorp.application.port.out.annotation;

import com.myhomelibcorp.application.annotation.remote.RemoteAnnotationExportBatchResult;
import com.myhomelibcorp.application.annotation.remote.RemoteAnnotationItem;

import java.util.List;

/** Narrow remote boundary for exporting annotation batches. Implementations must be idempotent for retries. */
public interface AnnotationRemoteExportPort {
    RemoteAnnotationExportBatchResult exportBatch(List<RemoteAnnotationItem> items);
    void testConnection();
}
