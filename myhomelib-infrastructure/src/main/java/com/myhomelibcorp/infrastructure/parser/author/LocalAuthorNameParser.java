package com.myhomelibcorp.infrastructure.parser.author;

import com.myhomelibcorp.domain.model.author.Author;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Conservative author-name cleanup used only by local document importers.
 *
 * <p>Local FB2/EPUB files are not authoritative identity sources. In the wild,
 * first/last fields are frequently swapped and some EPUB generators concatenate
 * two creators into one text node. This parser normalizes those common cases
 * without changing INPX/online author identity semantics.</p>
 */
public final class LocalAuthorNameParser {
    private static final Pattern MULTI_SEPARATOR = Pattern.compile(
            "\\s*(?:;|&|\\||\\s+and\\s+|\\s+и\\s+|\\s+та\\s+)\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CYRILLIC_WORD = Pattern.compile("[\\p{IsCyrillic}’'`-]+");
    private static final Pattern LIKELY_PATRONYMIC = Pattern.compile(
            ".*(?:ович|евич|йович|ич|овна|евна|ївна|івна|ична)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LIKELY_SURNAME = Pattern.compile(
            ".*(?:ов|ев|ёв|єв|ин|ын|ін|ский|цкий|ський|цький|енко|чук|щук|дзе|швили)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private LocalAuthorNameParser() { }

    /** Parses an unstructured creator value (for example EPUB dc:creator). */
    public static List<Author> parseCreators(String value) {
        String normalized = clean(value);
        if (normalized.isBlank()) return List.of();

        String[] explicit = MULTI_SEPARATOR.split(normalized);
        if (explicit.length > 1) {
            List<Author> out = new ArrayList<>(explicit.length);
            for (String part : explicit) out.addAll(parseSingleOrPair(part));
            return List.copyOf(out);
        }
        return parseSingleOrPair(normalized);
    }

    /** Parses one structured FB2 author and repairs only high-confidence malformed layouts. */
    public static List<Author> fromStructured(String firstName, String middleName, String lastName) {
        String first = clean(firstName);
        String middle = clean(middleName);
        String last = clean(lastName);

        if (first.isBlank() && middle.isBlank() && last.isBlank()) return List.of();

        // Some broken exporters put one complete person into first-name and another
        // complete person into last-name. Two two-word Cyrillic fields are a strong signal.
        if (twoCyrillicWords(first) && middle.isBlank() && twoCyrillicWords(last)) {
            List<Author> result = new ArrayList<>(2);
            result.addAll(parseSingleOrPair(first));
            result.addAll(parseSingleOrPair(last));
            return List.copyOf(result);
        }

        // Another observed malformed layout stores the second "Surname Name" pair
        // in middle-name. Do not split a normal patronymic or non-Cyrillic name.
        if (oneCyrillicWord(first) && oneCyrillicWord(last) && twoCyrillicWords(middle)
                && !looksLikePatronymic(middle)) {
            Author primary = normalizeOrientation(first, "", last);
            List<Author> result = new ArrayList<>(2);
            result.add(primary);
            result.addAll(parseSingleOrPair(middle));
            return List.copyOf(result);
        }

        return List.of(normalizeOrientation(first, middle, last));
    }

    private static List<Author> parseSingleOrPair(String raw) {
        String name = clean(raw);
        if (name.isBlank()) return List.of();

        // Conventional "Surname, Name [Middle]" syntax is one person.
        int comma = name.indexOf(',');
        if (comma > 0 && comma < name.length() - 1) {
            String last = clean(name.substring(0, comma));
            String right = clean(name.substring(comma + 1));
            String[] parts = words(right);
            String first = parts.length > 0 ? parts[0] : "";
            String middle = parts.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)) : "";
            return List.of(normalizeOrientation(first, middle, last));
        }

        String[] parts = words(name);
        if (parts.length == 1) return List.of(new Author("", "", parts[0]));
        if (parts.length == 2) return List.of(normalizeOrientation(parts[0], "", parts[1]));

        // Typical bad EPUB metadata: "Name Surname Name Surname" or the reversed
        // "Surname Name Surname Name". Restrict the heuristic to four Cyrillic words
        // and reject patronymic-looking middle tokens to avoid splitting normal names.
        if (parts.length == 4 && allCyrillic(parts)
                && !looksLikePatronymic(parts[1]) && !looksLikePatronymic(parts[2])) {
            return List.of(
                    normalizeOrientation(parts[0], "", parts[1]),
                    normalizeOrientation(parts[2], "", parts[3]));
        }

        String middle = String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
        return List.of(normalizeOrientation(parts[0], middle, parts[parts.length - 1]));
    }

    /**
     * Repairs a common FB2 field inversion only when the first field strongly looks
     * like a Cyrillic surname and the last field does not. Otherwise source order wins.
     */
    private static Author normalizeOrientation(String first, String middle, String last) {
        String cleanFirst = clean(first);
        String cleanMiddle = clean(middle);
        String cleanLast = clean(last);
        if (oneCyrillicWord(cleanFirst) && oneCyrillicWord(cleanLast)
                && looksLikeSurname(cleanFirst) && !looksLikeSurname(cleanLast)) {
            return new Author(cleanLast, cleanMiddle, cleanFirst);
        }
        return new Author(cleanFirst, cleanMiddle, cleanLast);
    }

    private static boolean looksLikeSurname(String value) {
        return LIKELY_SURNAME.matcher(value).matches();
    }

    private static boolean looksLikePatronymic(String value) {
        for (String word : words(value)) if (LIKELY_PATRONYMIC.matcher(word).matches()) return true;
        return false;
    }

    private static boolean oneCyrillicWord(String value) {
        String[] parts = words(value);
        return parts.length == 1 && CYRILLIC_WORD.matcher(parts[0]).matches();
    }

    private static boolean twoCyrillicWords(String value) {
        String[] parts = words(value);
        return parts.length == 2 && allCyrillic(parts);
    }

    private static boolean allCyrillic(String[] values) {
        for (String value : values) if (!CYRILLIC_WORD.matcher(value).matches()) return false;
        return values.length > 0;
    }

    private static String[] words(String value) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? new String[0] : cleaned.split("\\s+");
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }
}
