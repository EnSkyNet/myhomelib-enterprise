package com.myhomelibcorp.application.duplicate.fuzzy;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Conservative fuzzy duplicate scorer. It only produces suggestions; it never mutates or merges books.
 * Blocking/candidate retrieval is deliberately separate so the scoring contract can be evaluated on a
 * labeled corpus without database or UI side effects.
 */
@Component
public class FuzzyDuplicateDetector {
    public static final double DEFAULT_THRESHOLD = 0.88;

    public Optional<FuzzyDuplicateMatch> evaluate(Book left, Book right) {
        return evaluate(left, right, DEFAULT_THRESHOLD);
    }

    public Optional<FuzzyDuplicateMatch> evaluate(Book left, Book right, double threshold) {
        if (left == null || right == null || left.getId().equals(right.getId())) return Optional.empty();
        double boundedThreshold = Math.max(0.0, Math.min(1.0, threshold));

        String leftIsbn = left.getIsbn() == null ? "" : left.getIsbn().toString();
        String rightIsbn = right.getIsbn() == null ? "" : right.getIsbn().toString();
        if (!leftIsbn.isBlank() && leftIsbn.equals(rightIsbn)) {
            return Optional.of(new FuzzyDuplicateMatch(left.getId(), right.getId(), 1.0,
                    List.of(FuzzyDuplicateReason.ISBN_EXACT)));
        }

        String leftTitle = normalize(left.getTitle());
        String rightTitle = normalize(right.getTitle());
        String leftAuthor = normalize(primaryAuthor(left));
        String rightAuthor = normalize(primaryAuthor(right));
        if (leftTitle.isBlank() || rightTitle.isBlank() || leftAuthor.isBlank() || rightAuthor.isBlank()) {
            return Optional.empty();
        }

        double titleSimilarity = similarity(leftTitle, rightTitle);
        double authorSimilarity = similarity(leftAuthor, rightAuthor);
        boolean yearMatch = left.getYear() != null && right.getYear() != null && left.getYear().equals(right.getYear());

        List<FuzzyDuplicateReason> reasons = new ArrayList<>();
        if (leftTitle.equals(rightTitle)) reasons.add(FuzzyDuplicateReason.TITLE_EXACT);
        else if (titleSimilarity >= 0.78) reasons.add(FuzzyDuplicateReason.TITLE_SIMILAR);
        if (leftAuthor.equals(rightAuthor)) reasons.add(FuzzyDuplicateReason.AUTHOR_EXACT);
        else if (authorSimilarity >= 0.72) reasons.add(FuzzyDuplicateReason.AUTHOR_SIMILAR);
        if (yearMatch) reasons.add(FuzzyDuplicateReason.YEAR_MATCH);

        // Same normalized title without a compatible author is intentionally not enough: common titles
        // are the dominant source of false positives in large libraries.
        if (titleSimilarity < 0.78 || authorSimilarity < 0.72) return Optional.empty();

        double score = 0.82 * titleSimilarity + 0.15 * authorSimilarity + (yearMatch ? 0.03 : 0.0);
        if (score + 1e-12 < boundedThreshold) return Optional.empty();
        return Optional.of(new FuzzyDuplicateMatch(left.getId(), right.getId(), score, reasons));
    }

    public List<FuzzyDuplicateMatch> suggest(Book source, List<Book> candidates) {
        if (source == null || candidates == null || candidates.isEmpty()) return List.of();
        return candidates.stream()
                .map(candidate -> evaluate(source, candidate))
                .flatMap(Optional::stream)
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();
    }

    static double similarity(String left, String right) {
        if (left.equals(right)) return 1.0;
        if (left.isBlank() || right.isBlank()) return 0.0;
        double edit = normalizedLevenshtein(left, right);
        double tokens = tokenJaccard(left, right);
        // Token overlap helps reordered/retitled punctuation variants, while character edit
        // similarity remains authoritative for short one-token typos (e.g. Code -> Cod).
        return Math.max(edit, 0.60 * edit + 0.40 * tokens);
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    private static String primaryAuthor(Book book) {
        return book.getAuthors().stream().findFirst().map(Author::getFullName).orElse("");
    }

    private static double normalizedLevenshtein(String left, String right) {
        int max = Math.max(left.length(), right.length());
        if (max == 0) return 1.0;
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1.0 - ((double) previous[right.length()] / (double) max);
    }

    private static double tokenJaccard(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / (double) union.size();
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        for (String token : value.split(" ")) if (!token.isBlank()) result.add(token);
        return result;
    }
}
