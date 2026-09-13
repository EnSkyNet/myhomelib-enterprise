package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.domain.model.annotation.AnnotationAnchor;

import java.util.Objects;

/**
 * Application-facing annotation anchor value used by outer adapters such as the desktop Reader UI.
 * Keeps outer layers independent from the annotation domain implementation while preserving the
 * durable source-text coordinates and relocation hints.
 */
public record AnnotationAnchorData(
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
) {
    public AnnotationAnchorData {
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
        quote = Objects.requireNonNullElse(quote, "");
        prefix = Objects.requireNonNullElse(prefix, "");
        suffix = Objects.requireNonNullElse(suffix, "");
    }

    public boolean allowsArtifact(String candidateArtifactId) {
        return Objects.equals(artifactId, normalized(candidateArtifactId));
    }

    static AnnotationAnchorData fromDomain(AnnotationAnchor anchor) {
        return new AnnotationAnchorData(
                anchor.bookId(), anchor.artifactId(), anchor.chapterId(), anchor.chapterTitle(), anchor.paragraphId(),
                anchor.startOffset(), anchor.endOffset(), anchor.position(), anchor.quote(), anchor.prefix(), anchor.suffix());
    }

    AnnotationAnchor toDomain() {
        return new AnnotationAnchor(bookId, artifactId, chapterId, chapterTitle, paragraphId,
                startOffset, endOffset, position, quote, prefix, suffix);
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
}
