package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.domain.model.annotation.AnnotationAnchorRelocator;

import java.util.Optional;

/** Application boundary for renderer-independent quote/context relocation. */
public final class AnnotationReaderResolver {
    private AnnotationReaderResolver() { }

    public record ResolvedRange(long startOffset, long endOffset, boolean relocated) { }

    public static Optional<ResolvedRange> resolve(AnnotationReaderItem item, String artifactId, String text) {
        if (item == null || item.anchor() == null) return Optional.empty();
        return AnnotationAnchorRelocator.resolve(item.anchor().toDomain(), artifactId, text)
                .map(range -> new ResolvedRange(range.startOffset(), range.endOffset(), range.relocated()));
    }
}
