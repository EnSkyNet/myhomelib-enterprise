package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.domain.model.annotation.AnnotationAnchorRelocator;

import java.util.Optional;

/** Application boundary for renderer-independent quote/context relocation. */
public final class AnnotationReaderResolver {
    private static final int SAFE_CONTEXT_CHARS = 24;
    private static final int SAFE_CONTEXT_MARGIN = 8;

    private AnnotationReaderResolver() { }

    public record ResolvedRange(long startOffset, long endOffset, boolean relocated) { }

    /**
     * Candidate for an explicit cross-artifact rebind. A candidate is never applied automatically:
     * {@code safeToRebind} only means that the UI may offer a one-click confirmation because the
     * quote/context match is sufficiently unambiguous.
     */
    public record RebindCandidate(
            long startOffset,
            long endOffset,
            int occurrenceCount,
            int contextScore,
            boolean safeToRebind
    ) { }

    public static Optional<ResolvedRange> resolve(AnnotationReaderItem item, String artifactId, String text) {
        if (item == null || item.anchor() == null) return Optional.empty();
        return AnnotationAnchorRelocator.resolve(item.anchor().toDomain(), artifactId, text)
                .map(range -> new ResolvedRange(range.startOffset(), range.endOffset(), range.relocated()));
    }

    /**
     * Searches the currently opened representation by quote and surrounding context while ignoring
     * the persisted artifact binding. This method is deliberately conservative and is intended only
     * for a user-confirmed rebind workflow.
     */
    public static Optional<RebindCandidate> findRebindCandidate(AnnotationReaderItem item, String text) {
        if (item == null || item.anchor() == null || text == null) return Optional.empty();
        AnnotationAnchorData anchor = item.anchor();
        String quote = anchor.quote();
        if (quote == null || quote.isEmpty() || quote.length() > text.length()) return Optional.empty();

        int from = 0;
        int occurrences = 0;
        int bestStart = -1;
        int bestContext = Integer.MIN_VALUE;
        int secondContext = Integer.MIN_VALUE;
        long expected = Math.round(Math.max(0, text.length() - quote.length()) * anchor.position());
        long bestDistance = Long.MAX_VALUE;

        while (from <= text.length() - quote.length()) {
            int hit = text.indexOf(quote, from);
            if (hit < 0) break;
            occurrences++;
            int score = AnnotationAnchorRelocator.scoreContext(text, hit, quote.length(), anchor.prefix(), anchor.suffix());
            long distance = Math.abs((long) hit - expected);

            if (score > bestContext || (score == bestContext && distance < bestDistance)) {
                secondContext = bestContext;
                bestContext = score;
                bestDistance = distance;
                bestStart = hit;
            } else if (score > secondContext) {
                secondContext = score;
            }
            from = hit + 1;
        }
        if (bestStart < 0) return Optional.empty();

        boolean uniqueQuote = occurrences == 1;
        boolean strongContext = bestContext >= SAFE_CONTEXT_CHARS
                && (secondContext == Integer.MIN_VALUE || bestContext - secondContext >= SAFE_CONTEXT_MARGIN);
        return Optional.of(new RebindCandidate(
                bestStart,
                (long) bestStart + quote.length(),
                occurrences,
                Math.max(0, bestContext),
                uniqueQuote || strongContext));
    }


}
