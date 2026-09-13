package com.myhomelibcorp.domain.model.annotation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotationModelTest {

    @Test
    void modelIsSerializableAndKeepsRendererIndependentAnchorData() throws Exception {
        Annotation original = new Annotation(
                "ann-1", AnnotationType.HIGHLIGHT,
                new AnnotationAnchor("book-1", "artifact-1", "ch-2", "Chapter 2", "p-8",
                        120, 131, 0.42, "hello world", "before ", " after"),
                "#fff59d", "", Set.of("quote", "important"),
                Instant.parse("2026-09-09T10:00:00Z"), Instant.parse("2026-09-09T10:01:00Z"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(original); }
        Annotation restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Annotation) in.readObject();
        }

        assertThat(restored).isEqualTo(original);
        assertThat(restored.color()).isEqualTo("#FFF59D");
        assertThat(restored.anchor().allowsExactOffsetsFor("artifact-1")).isTrue();
        assertThat(restored.anchor().allowsExactOffsetsFor("artifact-2")).isFalse();
        AnnotationAnchor unbound = restored.anchor().withoutArtifactBinding();
        assertThat(unbound.allowsExactOffsetsFor(null)).isTrue();
        assertThat(unbound.allowsExactOffsetsFor("artifact-1")).isFalse();
        Annotation caseNormalized = new Annotation("ann-2", AnnotationType.HIGHLIGHT, original.anchor(), null, "",
                new java.util.LinkedHashSet<>(java.util.List.of("Important", "important", " Quote ")), original.createdAt(), original.updatedAt());
        assertThat(caseNormalized.tags()).containsExactlyInAnyOrder("Important", "Quote");
    }

    @Test
    void domainRejectsInvalidHighlightAndEmptyNote() {
        Instant now = Instant.parse("2026-09-09T10:00:00Z");
        AnnotationAnchor point = new AnnotationAnchor("book", null, null, null, null,
                10, 10, 0.5, "", "", "");
        assertThatThrownBy(() -> new Annotation("h", AnnotationType.HIGHLIGHT, point, null, "", Set.of(), now, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-empty text range");
        assertThatThrownBy(() -> new Annotation("n", AnnotationType.NOTE, point, null, "  ", Set.of(), now, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("note text");
        AnnotationAnchor rangeWithoutQuote = new AnnotationAnchor("book", "artifact", null, null, null,
                10, 12, 0.5, "", "", "");
        assertThatThrownBy(() -> new Annotation("h2", AnnotationType.HIGHLIGHT, rangeWithoutQuote, null, "", Set.of(), now, now))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selected quote text");
    }

    @Test
    void relocatorUsesOffsetsFirstThenQuoteAndContextAfterLayoutShift() {
        String oldText = "zero | before hello world after | end";
        int start = oldText.indexOf("hello world");
        AnnotationAnchor anchor = new AnnotationAnchor("book", "artifact", null, "Chapter", null,
                start, start + 11L, (double) start / oldText.length(), "hello world", "before ", " after");

        var exact = AnnotationAnchorRelocator.resolve(anchor, "artifact", oldText).orElseThrow();
        assertThat(exact.relocated()).isFalse();
        assertThat(oldText.substring((int) exact.startOffset(), (int) exact.endOffset())).isEqualTo("hello world");

        String shifted = "new heading\nzero | before hello world after | end";
        var relocated = AnnotationAnchorRelocator.resolve(anchor, "artifact", shifted).orElseThrow();
        assertThat(relocated.relocated()).isTrue();
        assertThat(shifted.substring((int) relocated.startOffset(), (int) relocated.endOffset())).isEqualTo("hello world");
        assertThat(AnnotationAnchorRelocator.resolve(anchor, "different-artifact", shifted)).isEmpty();
    }

    @Test
    void contextualMatchWinsWhenQuoteOccursMoreThanOnce() {
        String text = "A target Z ... before target after ... target end";
        AnnotationAnchor anchor = new AnnotationAnchor("book", null, null, null, null,
                999, 1005, 0.5, "target", "before ", " after");
        var range = AnnotationAnchorRelocator.resolve(anchor, null, text).orElseThrow();
        assertThat(text.substring((int) range.startOffset(), (int) range.endOffset())).isEqualTo("target");
        assertThat(text.substring(Math.max(0, (int) range.startOffset() - 7), (int) range.startOffset()))
                .isEqualTo("before ");
    }
}
