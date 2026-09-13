package com.myhomelibcorp.domain.model.annotation;

import java.io.Serial;
import java.io.Serializable;

/**
 * Renderer-independent text anchor.
 *
 * <p>The absolute offsets are the fast path for reopening an unchanged artifact. The quote and
 * surrounding context are durable relocation hints when pagination/layout changes. An artifact id
 * is deliberately optional: if it is present, callers must not silently apply offsets to another
 * artifact representation of the same logical book.</p>
 */
public record AnnotationAnchor(
        String bookId,
        String artifactId,
        String chapterId,
        String chapterTitle,
        String paragraphId,
        long startOffset,
        long endOffset,
        double position,
        String quote,
        String prefix,
        String suffix
) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public AnnotationAnchor {
        bookId = required(bookId, "bookId");
        artifactId = normalized(artifactId);
        chapterId = normalized(chapterId);
        chapterTitle = normalized(chapterTitle);
        paragraphId = normalized(paragraphId);
        if (startOffset < 0) throw new IllegalArgumentException("startOffset must be >= 0");
        if (endOffset < startOffset) throw new IllegalArgumentException("endOffset must be >= startOffset");
        if (!Double.isFinite(position) || position < 0.0 || position > 1.0) {
            throw new IllegalArgumentException("position must be within [0,1]");
        }
        quote = safe(quote);
        prefix = safe(prefix);
        suffix = safe(suffix);
    }

    public boolean hasRange() {
        return endOffset > startOffset;
    }

    public boolean isBoundToArtifact() {
        return artifactId != null;
    }

    /**
     * Exact offsets are representation-specific. They are reusable only when the candidate binding
     * exactly matches the stored binding. An unbound anchor therefore does not gain implicit trust
     * when a concrete artifact is later opened; it must first be explicitly rebound.
     */
    public boolean allowsExactOffsetsFor(String candidateArtifactId) {
        return java.util.Objects.equals(artifactId, normalized(candidateArtifactId));
    }

    public AnnotationAnchor withoutArtifactBinding() {
        return new AnnotationAnchor(bookId, null, chapterId, chapterTitle, paragraphId,
                startOffset, endOffset, position, quote, prefix, suffix);
    }

    /** Explicitly binds a freshly resolved location to an artifact; no implicit cross-artifact offset reuse. */
    public AnnotationAnchor rebind(String newArtifactId, long newStartOffset, long newEndOffset,
                                   double newPosition, String newQuote, String newPrefix, String newSuffix) {
        String bound = required(newArtifactId, "artifactId");
        return new AnnotationAnchor(bookId, bound, chapterId, chapterTitle, paragraphId,
                newStartOffset, newEndOffset, newPosition, newQuote, newPrefix, newSuffix);
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized == null) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalized(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
