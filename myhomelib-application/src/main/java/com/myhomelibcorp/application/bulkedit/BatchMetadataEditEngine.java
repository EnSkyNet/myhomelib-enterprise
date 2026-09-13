package com.myhomelibcorp.application.bulkedit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Pure, reusable transformation engine; database/JavaFX concerns stay outside this class. */
public final class BatchMetadataEditEngine {
    private static final int MAX_REGEX_LENGTH = 512;
    private static final Set<BatchMetadataEditField> TEXT_FIELDS = Set.of(
            BatchMetadataEditField.TITLE,
            BatchMetadataEditField.SERIES,
            BatchMetadataEditField.PUBLISHER,
            BatchMetadataEditField.TAGS,
            BatchMetadataEditField.ANNOTATION);

    private BatchMetadataEditEngine() {
    }

    public static PreparedPlan prepare(List<BatchMetadataEditRule> rules) {
        if (rules == null || rules.isEmpty()) throw new IllegalArgumentException("At least one batch edit rule is required");
        if (rules.size() > 32) throw new IllegalArgumentException("Too many batch edit rules: " + rules.size());
        List<PreparedRule> prepared = new ArrayList<>(rules.size());
        for (BatchMetadataEditRule rule : rules) prepared.add(prepareRule(Objects.requireNonNull(rule, "rule")));
        return new PreparedPlan(List.copyOf(prepared));
    }

    public static List<BatchMetadataEditField> changedFields(BatchMetadataEditableSnapshot before,
                                                              BatchMetadataEditableSnapshot after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        List<BatchMetadataEditField> changed = new ArrayList<>();
        if (!Objects.equals(before.title(), after.title())) changed.add(BatchMetadataEditField.TITLE);
        if (!Objects.equals(before.series(), after.series())) changed.add(BatchMetadataEditField.SERIES);
        if (!Objects.equals(before.publisher(), after.publisher())) changed.add(BatchMetadataEditField.PUBLISHER);
        if (!Objects.equals(before.tags(), after.tags())) changed.add(BatchMetadataEditField.TAGS);
        if (!Objects.equals(before.annotation(), after.annotation())) changed.add(BatchMetadataEditField.ANNOTATION);
        if (!Objects.equals(before.language(), after.language())) changed.add(BatchMetadataEditField.LANGUAGE);
        if (!Objects.equals(before.genres(), after.genres())) changed.add(BatchMetadataEditField.GENRES);
        if (!Objects.equals(before.year(), after.year())) changed.add(BatchMetadataEditField.YEAR);
        return List.copyOf(changed);
    }

    private static PreparedRule prepareRule(BatchMetadataEditRule rule) {
        if (rule.field() == null) throw new IllegalArgumentException("Batch edit field is required");
        if (rule.action() == null) throw new IllegalArgumentException("Batch edit action is required");

        if (rule.field() == BatchMetadataEditField.LANGUAGE) {
            if (rule.action() != BatchMetadataEditAction.SET && rule.action() != BatchMetadataEditAction.CLEAR) {
                throw new IllegalArgumentException("Language supports only SET/CLEAR");
            }
            String value = rule.action() == BatchMetadataEditAction.CLEAR ? "und" : required(rule.value(), "Language is required");
            return new PreparedRule(rule.field(), rule.action(), value.trim().toLowerCase(Locale.ROOT), null, "");
        }
        if (rule.field() == BatchMetadataEditField.YEAR) {
            if (rule.action() != BatchMetadataEditAction.SET && rule.action() != BatchMetadataEditAction.CLEAR) {
                throw new IllegalArgumentException("Year supports only SET/CLEAR");
            }
            if (rule.action() == BatchMetadataEditAction.CLEAR) {
                return new PreparedRule(rule.field(), rule.action(), null, null, "");
            }
            String value = required(rule.value(), "Year is required").trim();
            try {
                int year = Integer.parseInt(value);
                if (year < 0 || year > 9999) throw new NumberFormatException("range");
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Year must be an integer from 0 to 9999: " + value, invalid);
            }
            return new PreparedRule(rule.field(), rule.action(), value, null, "");
        }
        if (rule.field() == BatchMetadataEditField.GENRES) {
            if (rule.action() != BatchMetadataEditAction.SET && rule.action() != BatchMetadataEditAction.CLEAR) {
                throw new IllegalArgumentException("Genres support only SET/CLEAR");
            }
            String value = rule.action() == BatchMetadataEditAction.CLEAR ? "" : required(rule.value(), "Genre codes are required");
            return new PreparedRule(rule.field(), rule.action(), value, null, "");
        }
        if (!TEXT_FIELDS.contains(rule.field())) throw new IllegalArgumentException("Unsupported batch field: " + rule.field());
        if (rule.field() == BatchMetadataEditField.TITLE && rule.action() == BatchMetadataEditAction.CLEAR) {
            throw new IllegalArgumentException("Title cannot be cleared");
        }

        return switch (rule.action()) {
            case SET -> new PreparedRule(rule.field(), rule.action(),
                    rule.field() == BatchMetadataEditField.TITLE
                            ? required(rule.value(), "Title is required")
                            : nullToEmpty(rule.value()), null, "");
            case CLEAR -> new PreparedRule(rule.field(), rule.action(), "", null, "");
            case TRIM, CAPITALIZE -> new PreparedRule(rule.field(), rule.action(), null, null, "");
            case REGEX_REPLACE -> {
                String expression = required(rule.pattern(), "Regex pattern is required");
                if (expression.length() > MAX_REGEX_LENGTH) {
                    throw new IllegalArgumentException("Regex pattern is longer than " + MAX_REGEX_LENGTH + " characters");
                }
                try {
                    yield new PreparedRule(rule.field(), rule.action(), null, Pattern.compile(expression), nullToEmpty(rule.replacement()));
                } catch (PatternSyntaxException invalid) {
                    throw new IllegalArgumentException("Invalid regex: " + invalid.getDescription(), invalid);
                }
            }
        };
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public static final class PreparedPlan {
        private final List<PreparedRule> rules;

        private PreparedPlan(List<PreparedRule> rules) {
            this.rules = rules;
        }

        public BatchMetadataEditableSnapshot apply(BatchMetadataEditableSnapshot source) {
            Objects.requireNonNull(source, "source");
            Mutable value = new Mutable(source);
            for (PreparedRule rule : rules) value.apply(rule);
            BatchMetadataEditableSnapshot result = value.snapshot();
            if (result.title() == null || result.title().isBlank()) {
                throw new IllegalArgumentException("Batch edit would make a book title blank");
            }
            return result;
        }
    }

    private record PreparedRule(BatchMetadataEditField field, BatchMetadataEditAction action,
                                String value, Pattern pattern, String replacement) {
    }

    private static final class Mutable {
        private String title;
        private String series;
        private Integer sequenceNumber;
        private String language;
        private Integer year;
        private String publisher;
        private String tags;
        private String annotation;
        private List<BatchMetadataGenreSnapshot> genres;

        private Mutable(BatchMetadataEditableSnapshot source) {
            title = source.title();
            series = source.series();
            sequenceNumber = source.sequenceNumber();
            language = source.language();
            year = source.year();
            publisher = source.publisher();
            tags = source.tags();
            annotation = source.annotation();
            genres = source.genres();
        }

        private void apply(PreparedRule rule) {
            switch (rule.field()) {
                case TITLE -> title = transformText(title, rule);
                case SERIES -> series = emptyToNull(transformText(series, rule));
                case PUBLISHER -> publisher = transformText(publisher, rule);
                case TAGS -> tags = transformText(tags, rule);
                case ANNOTATION -> annotation = transformText(annotation, rule);
                case LANGUAGE -> language = rule.action() == BatchMetadataEditAction.CLEAR ? "und" : rule.value();
                case YEAR -> year = rule.action() == BatchMetadataEditAction.CLEAR ? null : Integer.valueOf(rule.value());
                case GENRES -> genres = rule.action() == BatchMetadataEditAction.CLEAR ? List.of() : parseGenres(rule.value());
            }
        }

        private BatchMetadataEditableSnapshot snapshot() {
            return new BatchMetadataEditableSnapshot(title, series, sequenceNumber,
                    language == null || language.isBlank() ? "und" : language,
                    year, nullToEmpty(publisher), nullToEmpty(tags), nullToEmpty(annotation), genres);
        }
    }

    private static String transformText(String source, PreparedRule rule) {
        String value = nullToEmpty(source);
        return switch (rule.action()) {
            case SET -> rule.value();
            case CLEAR -> "";
            case REGEX_REPLACE -> rule.pattern().matcher(value).replaceAll(rule.replacement());
            case TRIM -> value.strip();
            case CAPITALIZE -> capitalizeWords(value);
        };
    }

    private static List<BatchMetadataGenreSnapshot> parseGenres(String raw) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String token : nullToEmpty(raw).split(",")) {
            String code = token.trim();
            if (!code.isBlank()) codes.add(code);
        }
        if (codes.isEmpty()) throw new IllegalArgumentException("At least one genre code is required");
        return codes.stream().map(code -> new BatchMetadataGenreSnapshot(code, code, null, code)).toList();
    }

    private static String capitalizeWords(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean atWordStart = true;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            if (Character.isLetterOrDigit(cp)) {
                out.appendCodePoint(atWordStart ? Character.toTitleCase(cp) : cp);
                atWordStart = false;
            } else {
                out.appendCodePoint(cp);
                atWordStart = true;
            }
            offset += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
