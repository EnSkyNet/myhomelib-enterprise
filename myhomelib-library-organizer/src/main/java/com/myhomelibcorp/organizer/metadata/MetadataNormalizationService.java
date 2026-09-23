package com.myhomelibcorp.organizer.metadata;

import com.myhomelibcorp.organizer.detect.DetectionConfidence;
import com.myhomelibcorp.shared.util.SafeFileNameSupport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public final class MetadataNormalizationService {
    private static final Pattern SERIES_LABEL = Pattern.compile("(?iu)^(?:серія|серия|series)\\s*:\\s*");
    private static final List<Pattern> NUMBER_PATTERNS = List.of(
            Pattern.compile("(?iu)^\\s*(?:#|№\\s*|book\\s+|книга\\s+|том\\s+)?(\\d+(?:[.,]\\d+)?)\\s*$"),
            Pattern.compile("(?u)^\\s*[\\[(]\\s*(\\d+(?:[.,]\\d+)?)\\s*[\\])]\\s*$"));

    public NormalizedBookIdentity normalize(ExtractedBookMetadata raw) {
        return normalize(raw, true);
    }

    public NormalizedBookIdentity normalize(ExtractedBookMetadata raw, boolean enabled) {
        if (!enabled) return preserveRawIdentity(raw);
        List<NormalizationIssue> issues = new ArrayList<>();
        String title = clean(raw.rawTitle());
        if (title.isBlank()) issues.add(new NormalizationIssue("TITLE_MISSING", "Назва книги не визначена"));

        List<NormalizedAuthor> authors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : raw.rawAuthors()) {
            NormalizedAuthor author = normalizeAuthor(value, issues);
            String key = author.displayName().toLowerCase(Locale.ROOT);
            if (!author.displayName().isBlank() && seen.add(key)) authors.add(author);
        }
        if (authors.isEmpty()) issues.add(new NormalizationIssue("AUTHOR_MISSING", "Автор не визначений"));

        String series = SERIES_LABEL.matcher(clean(raw.rawSeries())).replaceFirst("").strip();
        BigDecimal number = parseSeriesNumber(raw.rawSeriesNumber(), issues);
        String language = clean(raw.language()).toLowerCase(Locale.ROOT);
        String isbn = clean(raw.isbn()).replaceAll("(?i)^isbn(?:-1[03])?\\s*:?\\s*", "");

        int confidence = switch (raw.sourceConfidence()) {
            case CERTAIN -> 95; case HIGH -> 85; case MEDIUM -> 70; case LOW -> 50; case UNKNOWN -> 25;
        };
        if (title.isBlank()) confidence -= 25;
        if (authors.isEmpty()) confidence -= 20;
        confidence -= Math.min(20, raw.warnings().size() * 3);
        return new NormalizedBookIdentity(title, authors, series, number, language, isbn,
                Math.max(0, confidence), issues);
    }

    private NormalizedBookIdentity preserveRawIdentity(ExtractedBookMetadata raw) {
        List<NormalizationIssue> issues = new ArrayList<>();
        String title = preserve(raw.rawTitle());
        if (title.isBlank()) issues.add(new NormalizationIssue("TITLE_MISSING", "Назва книги не визначена"));
        List<NormalizedAuthor> authors = raw.rawAuthors().stream()
                .map(this::preserve)
                .filter(value -> !value.isBlank())
                .map(value -> new NormalizedAuthor(value, "", "", "", false))
                .toList();
        if (authors.isEmpty()) issues.add(new NormalizationIssue("AUTHOR_MISSING", "Автор не визначений"));
        String series = preserve(raw.rawSeries());
        BigDecimal number = simpleSeriesNumber(raw.rawSeriesNumber());
        if (number == null && !preserve(raw.rawSeriesNumber()).isBlank()) {
            issues.add(new NormalizationIssue("SERIES_NUMBER_PRESERVED", "Номер серії не нормалізовано за налаштуванням профілю"));
        }
        int confidence = switch (raw.sourceConfidence()) {
            case CERTAIN -> 95; case HIGH -> 85; case MEDIUM -> 70; case LOW -> 50; case UNKNOWN -> 25;
        };
        if (title.isBlank()) confidence -= 25;
        if (authors.isEmpty()) confidence -= 20;
        confidence -= Math.min(20, raw.warnings().size() * 3);
        return new NormalizedBookIdentity(title, authors, series, number, preserve(raw.language()), preserve(raw.isbn()),
                Math.max(0, confidence), issues);
    }

    private BigDecimal simpleSeriesNumber(String raw) {
        String value = preserve(raw);
        if (!value.matches("\\d+(?:[.,]\\d+)?")) return null;
        try { return new BigDecimal(value.replace(',', '.')).stripTrailingZeros(); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String preserve(String value) { return value == null ? "" : value.strip(); }

    private NormalizedAuthor normalizeAuthor(String raw, List<NormalizationIssue> issues) {
        String value = clean(raw).replaceAll("[,.;]{2,}", ",");
        if (value.isBlank()) return new NormalizedAuthor("", "", "", "", false);
        int comma = value.indexOf(',');
        if (comma > 0 && comma == value.lastIndexOf(',')) {
            String family = clean(value.substring(0, comma));
            String remainder = clean(value.substring(comma + 1));
            if (!family.isBlank() && !remainder.isBlank()) {
                String[] parts = remainder.split(" ");
                String given = parts[0];
                String middle = parts.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)) : "";
                return new NormalizedAuthor(String.join(" ", List.of(given, middle, family).stream().filter(s -> !s.isBlank()).toList()),
                        given, middle, family, true);
            }
        }
        // Without a reliable delimiter, preserve display order instead of guessing family/given semantics.
        issues.add(new NormalizationIssue("AUTHOR_UNSTRUCTURED", "Ім’я автора збережено без перестановки: " + value));
        return new NormalizedAuthor(value, "", "", "", false);
    }

    private BigDecimal parseSeriesNumber(String raw, List<NormalizationIssue> issues) {
        String value = clean(raw);
        if (value.isBlank()) return null;
        for (Pattern pattern : NUMBER_PATTERNS) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.matches()) {
                try { return new BigDecimal(matcher.group(1).replace(',', '.')).stripTrailingZeros(); }
                catch (NumberFormatException ignored) { break; }
            }
        }
        issues.add(new NormalizationIssue("SERIES_NUMBER_UNPARSED", "Не вдалося однозначно визначити номер серії: " + value));
        return null;
    }

    public String clean(String value) {
        if (value == null) return "";
        return SafeFileNameSupport.normalizeNfc(Normalizer.normalize(value, Normalizer.Form.NFC))
                .replace('\u00A0', ' ').replaceAll("\\s+", " ").strip();
    }
}
