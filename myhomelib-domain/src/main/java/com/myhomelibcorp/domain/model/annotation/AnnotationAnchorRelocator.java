package com.myhomelibcorp.domain.model.annotation;

import java.util.Optional;

/** Pure text relocation helper used when layout/pagination changed after an annotation was stored. */
public final class AnnotationAnchorRelocator {
    private AnnotationAnchorRelocator() { }

    public record ResolvedRange(long startOffset, long endOffset, boolean relocated) { }

    public static Optional<ResolvedRange> resolve(AnnotationAnchor anchor, String artifactId, String text) {
        if (anchor == null || text == null) return Optional.empty();
        if (!anchor.allowsExactOffsetsFor(artifactId)) return Optional.empty();

        String quote = anchor.quote();
        if (quote.isEmpty()) {
            if (anchor.endOffset() <= text.length()) {
                return Optional.of(new ResolvedRange(anchor.startOffset(), anchor.endOffset(), false));
            }
            return Optional.empty();
        }

        if (anchor.endOffset() <= text.length()) {
            int start = Math.toIntExact(anchor.startOffset());
            int end = Math.toIntExact(anchor.endOffset());
            if (text.substring(start, end).equals(quote)) {
                return Optional.of(new ResolvedRange(start, end, false));
            }
        }

        int best = bestQuoteMatch(text, quote, anchor.prefix(), anchor.suffix(), anchor.position());
        if (best < 0) return Optional.empty();
        return Optional.of(new ResolvedRange(best, (long) best + quote.length(), true));
    }

    private static int bestQuoteMatch(String text, String quote, String prefix, String suffix, double position) {
        int from = 0;
        int best = -1;
        int bestContextScore = Integer.MIN_VALUE;
        long expected = Math.round(Math.max(0, text.length() - quote.length()) * position);
        long bestDistance = Long.MAX_VALUE;
        while (from <= text.length() - quote.length()) {
            int hit = text.indexOf(quote, from);
            if (hit < 0) break;
            int contextScore = scoreContext(text, hit, quote.length(), prefix, suffix);
            long distance = Math.abs((long) hit - expected);
            if (contextScore > bestContextScore || (contextScore == bestContextScore && distance < bestDistance)) {
                best = hit;
                bestContextScore = contextScore;
                bestDistance = distance;
            }
            from = hit + 1;
        }
        return best;
    }

    public static int scoreContext(String text, int start, int quoteLength, String prefix, String suffix) {
        int score = 0;
        if (prefix != null && !prefix.isEmpty()) {
            int take = Math.min(prefix.length(), start);
            if (take > 0 && text.regionMatches(start - take, prefix, prefix.length() - take, take)) score += take;
        }
        if (suffix != null && !suffix.isEmpty()) {
            int after = start + quoteLength;
            int take = Math.min(suffix.length(), text.length() - after);
            if (take > 0 && text.regionMatches(after, suffix, 0, take)) score += take;
        }
        return score;
    }
}
