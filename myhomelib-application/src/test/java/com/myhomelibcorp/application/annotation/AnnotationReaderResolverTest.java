package com.myhomelibcorp.application.annotation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationReaderResolverTest {

    @Test
    void crossArtifactCandidateIsSafeWhenQuoteOccursOnce() {
        String text = "prefix unique quote suffix";
        AnnotationReaderItem item = item("other-artifact", "unique quote", "prefix ", " suffix", 0.5);

        var candidate = AnnotationReaderResolver.findRebindCandidate(item, text).orElseThrow();

        assertThat(candidate.startOffset()).isEqualTo(text.indexOf("unique quote"));
        assertThat(candidate.endOffset()).isEqualTo(text.indexOf("unique quote") + "unique quote".length());
        assertThat(candidate.occurrenceCount()).isEqualTo(1);
        assertThat(candidate.safeToRebind()).isTrue();
    }

    @Test
    void repeatedQuoteWithoutContextIsNotSafeToRebind() {
        String text = "quote middle quote";
        AnnotationReaderItem item = item("other-artifact", "quote", "", "", 0.5);

        var candidate = AnnotationReaderResolver.findRebindCandidate(item, text).orElseThrow();

        assertThat(candidate.occurrenceCount()).isEqualTo(2);
        assertThat(candidate.contextScore()).isZero();
        assertThat(candidate.safeToRebind()).isFalse();
    }

    @Test
    void repeatedQuoteWithStrongUniqueContextChoosesCorrectOccurrence() {
        String longPrefix = "0123456789abcdefghijklmn"; // 24 characters
        String text = "quote filler " + longPrefix + "quote suffix";
        AnnotationReaderItem item = item("other-artifact", "quote", longPrefix, " suffix", 0.8);

        var candidate = AnnotationReaderResolver.findRebindCandidate(item, text).orElseThrow();

        assertThat(candidate.occurrenceCount()).isEqualTo(2);
        assertThat(candidate.startOffset()).isEqualTo(text.lastIndexOf("quote"));
        assertThat(candidate.contextScore()).isGreaterThanOrEqualTo(24);
        assertThat(candidate.safeToRebind()).isTrue();
    }

    private static AnnotationReaderItem item(
            String artifactId,
            String quote,
            String prefix,
            String suffix,
            double position
    ) {
        return new AnnotationReaderItem(
                "ann-1",
                new AnnotationAnchorData("book-1", artifactId, "ch-1", "Chapter", "p-1",
                        0, quote.length(), position, quote, prefix, suffix),
                "#FFF59D",
                AnnotationReaderType.NOTE,
                "memo",
                Set.of("tag"),
                Instant.parse("2026-09-14T12:00:00Z"));
    }
}
