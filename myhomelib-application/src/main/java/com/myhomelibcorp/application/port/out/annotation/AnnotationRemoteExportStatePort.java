package com.myhomelibcorp.application.port.out.annotation;

import java.util.Map;
import java.util.Optional;

/** Local checkpoint keyed by stable annotation id. Does not contain annotation text or credentials. */
public interface AnnotationRemoteExportStatePort {
    Optional<String> exportedFingerprint(String remote, String annotationId);
    void markExported(String remote, Map<String, String> fingerprints);
}
