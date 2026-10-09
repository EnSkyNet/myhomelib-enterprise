package com.myhomelibcorp.domain.model.annotation;

import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Pure text relocation helper used when layout/pagination changed after an annotation was stored. */
public final class AnnotationAnchorRelocator {
    private AnnotationAnchorRelocator() { }

    public record ResolvedRange(long startOffset, long endOffset, boolean relocated) { }
    public record TextMatch(int startOffset, int endOffset) { }

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

        TextMatch best = bestQuoteMatch(text, quote, anchor.prefix(), anchor.suffix(), anchor.position());
        if (best == null) return Optional.empty();
        return Optional.of(new ResolvedRange(best.startOffset(), best.endOffset(), true));
    }

    private static TextMatch bestQuoteMatch(String text, String quote, String prefix, String suffix, double position) {
        TextMatch best = null;
        int bestContextScore = Integer.MIN_VALUE;
        long expected = Math.round(Math.max(0, text.length() - quote.length()) * position);
        long bestDistance = Long.MAX_VALUE;
        for (TextMatch hit : findQuoteMatches(text, quote)) {
            int contextScore = scoreContext(text, hit.startOffset(), hit.endOffset() - hit.startOffset(), prefix, suffix);
            long distance = Math.abs((long) hit.startOffset() - expected);
            if (contextScore > bestContextScore || (contextScore == bestContextScore && distance < bestDistance)) {
                best = hit;
                bestContextScore = contextScore;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Finds quote occurrences while treating NFC/NFD spellings as canonically equivalent. */
    public static List<TextMatch> findQuoteMatches(String text, String quote) {
        if (text == null || quote == null || quote.isEmpty()) return List.of();
        if (Normalizer.isNormalized(text, Normalizer.Form.NFC)
                && Normalizer.isNormalized(quote, Normalizer.Form.NFC)) {
            return exactMatches(text, quote);
        }

        String normalizedQuote = Normalizer.normalize(quote, Normalizer.Form.NFC);
        if (normalizedQuote.isEmpty()) return List.of();
        StringBuilder normalizedText = new StringBuilder(text.length());
        Map<Integer, Integer> originalBoundary = new HashMap<>();
        originalBoundary.put(0, 0);
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(text);
        int start = characters.first();
        for (int end = characters.next(); end != BreakIterator.DONE; start = end, end = characters.next()) {
            normalizedText.append(Normalizer.normalize(text.substring(start, end), Normalizer.Form.NFC));
            originalBoundary.put(normalizedText.length(), end);
        }

        List<TextMatch> matches = new ArrayList<>();
        int from = 0;
        while (from <= normalizedText.length() - normalizedQuote.length()) {
            int hit = normalizedText.indexOf(normalizedQuote, from);
            if (hit < 0) break;
            Integer originalStart = originalBoundary.get(hit);
            Integer originalEnd = originalBoundary.get(hit + normalizedQuote.length());
            if (originalStart != null && originalEnd != null) {
                matches.add(new TextMatch(originalStart, originalEnd));
            }
            from = hit + 1;
        }
        return List.copyOf(matches);
    }

    private static List<TextMatch> exactMatches(String text, String quote) {
        if (quote.length() > text.length()) return List.of();
        List<TextMatch> matches = new ArrayList<>();
        int from = 0;
        while (from <= text.length() - quote.length()) {
            int hit = text.indexOf(quote, from);
            if (hit < 0) break;
            matches.add(new TextMatch(hit, hit + quote.length()));
            from = hit + 1;
        }
        return List.copyOf(matches);
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
