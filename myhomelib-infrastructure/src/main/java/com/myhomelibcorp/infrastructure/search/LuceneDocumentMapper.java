package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.customfield.CustomFieldValue;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.util.BytesRef;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Maps immutable catalogue snapshots to the Lucene document schema. */
final class LuceneDocumentMapper {
    static final String SCHEMA_VERSION = "custom-fields-v2-activity";

    Document toDocument(BookSnapshot snapshot) {
        Document doc = new Document();
        doc.add(new StringField("id", snapshot.getId().asString(), Field.Store.YES));
        doc.add(new TextField("title", safe(snapshot.getTitle()), Field.Store.NO));
        doc.add(new TextField("authors", safe(snapshot.getAuthorsText()), Field.Store.NO));
        doc.add(new TextField("series", safe(snapshot.getSeries()), Field.Store.NO));
        doc.add(new TextField("genres", safe(snapshot.getGenresText()), Field.Store.NO));
        doc.add(new TextField("keywords", safe(snapshot.getKeywords()), Field.Store.NO));
        doc.add(new TextField("annotation", safe(snapshot.getAnnotation()), Field.Store.NO));
        doc.add(new TextField("file_name", safe(snapshot.getFileName()), Field.Store.NO));
        doc.add(new TextField("publisher", safe(snapshot.getPublisher()), Field.Store.NO));
        doc.add(new StringField("title_exact", normalizedExact(snapshot.getTitle()), Field.Store.NO));
        doc.add(new StringField("series_exact", normalizedExact(snapshot.getSeries()), Field.Store.NO));
        doc.add(new StringField("publisher_exact", normalizedExact(snapshot.getPublisher()), Field.Store.NO));
        doc.add(new StringField("keyword_exact", normalizedExact(snapshot.getKeywords()), Field.Store.NO));
        addDelimitedExactValues(doc, "author_name_exact", snapshot.getAuthorsText());
        addDelimitedExactValues(doc, "genre_name_exact", snapshot.getGenresText());
        doc.add(new TextField("translators", safe(snapshot.getTranslators()), Field.Store.NO));
        doc.add(new TextField("city", safe(snapshot.getCity()), Field.Store.NO));
        doc.add(new StringField("lib_id", safe(snapshot.getLibId()), Field.Store.NO));
        doc.add(new TextField("isbn", safe(snapshot.getIsbn()), Field.Store.NO));
        doc.add(new TextField("source", safe(snapshot.getSourceUrl()), Field.Store.NO));
        doc.add(new StringField("language", safe(snapshot.getLanguage()).toLowerCase(Locale.ROOT), Field.Store.NO));
        addSpaceSeparatedIds(doc, "author_id", snapshot.getAuthorIds());
        addSpaceSeparatedIds(doc, "genre_id", snapshot.getGenreIds());

        int libraryRate = snapshot.getLibraryRate() == null ? 0 : snapshot.getLibraryRate();
        doc.add(new IntPoint("library_rate_num", libraryRate));
        doc.add(new StringField("library_rate", Integer.toString(libraryRate), Field.Store.NO));
        int rate = snapshot.getRate() == null ? 0 : snapshot.getRate();
        doc.add(new IntPoint("rate_num", rate));
        doc.add(new NumericDocValuesField("rate_sort", rate));
        doc.add(new StringField("rate", Integer.toString(rate), Field.Store.NO));
        int progress = snapshot.getProgress() == null ? 0 : snapshot.getProgress();
        doc.add(new IntPoint("progress_num", progress));
        doc.add(new NumericDocValuesField("progress_sort", progress));
        doc.add(new StringField("read", progress >= 100 ? "1" : "0", Field.Store.NO));
        doc.add(new StringField("format", detectFormat(snapshot.getFileName()), Field.Store.NO));
        int year = snapshot.getYear() == null ? 0 : snapshot.getYear();
        doc.add(new IntPoint("year_num", year));
        doc.add(new NumericDocValuesField("year_sort", year));
        doc.add(new StringField("year", formatYear(year), Field.Store.NO));
        long createdDay = snapshot.getCreatedAt() == null ? Long.MIN_VALUE : snapshot.getCreatedAt().toLocalDate().toEpochDay();
        doc.add(new NumericDocValuesField("created_sort", createdDay));
        if (snapshot.getCreatedAt() != null) {
            doc.add(new LongPoint("created_day", createdDay));
            doc.add(new StringField("created", snapshot.getCreatedAt().toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE), Field.Store.NO));
        }
        doc.add(new SortedDocValuesField("title_sort", new BytesRef(normalizedExact(snapshot.getTitle()))));
        doc.add(new SortedDocValuesField("author_sort", new BytesRef(normalizedExact(snapshot.getAuthorsText()))));
        doc.add(new SortedDocValuesField("series_sort", new BytesRef(normalizedExact(snapshot.getSeries()))));
        doc.add(new SortedDocValuesField("id_sort", new BytesRef(snapshot.getId().asString())));
        doc.add(new StringField("local", snapshot.isLocal() ? "1" : "0", Field.Store.NO));
        doc.add(new StringField("deleted", snapshot.isDeleted() ? "1" : "0", Field.Store.NO));
        doc.add(new StringField("has_note", snapshot.getNoteCount() > 0 ? "1" : "0", Field.Store.NO));
        doc.add(new StringField("has_highlight", snapshot.getHighlightCount() > 0 ? "1" : "0", Field.Store.NO));
        doc.add(new StringField("has_annotation", (snapshot.getNoteCount() + snapshot.getHighlightCount()) > 0 ? "1" : "0", Field.Store.NO));
        addCustomFields(doc, snapshot);
        return doc;
    }

    private static void addCustomFields(Document doc, BookSnapshot snapshot) {
        if (snapshot.getCustomFieldValues() == null) return;
        for (CustomFieldValue field : snapshot.getCustomFieldValues()) {
            if (field == null || field.value() == null || field.value().isBlank()) continue;
            String suffix = Long.toString(field.definitionId());
            String value = field.value().trim();
            switch (field.type()) {
                case TEXT -> {
                    doc.add(new TextField("cf_text_" + suffix, value, Field.Store.NO));
                    doc.add(new StringField("cf_exact_" + suffix, normalizedExact(value), Field.Store.NO));
                }
                case ENUM -> doc.add(new StringField("cf_exact_" + suffix, normalizedExact(value), Field.Store.NO));
                case NUMBER -> {
                    try { doc.add(new DoublePoint("cf_num_" + suffix, Double.parseDouble(value))); }
                    catch (NumberFormatException ignored) { }
                }
                case BOOL -> doc.add(new StringField("cf_bool_" + suffix, Boolean.toString(Boolean.parseBoolean(value)), Field.Store.NO));
                case DATE -> {
                    try { doc.add(new LongPoint("cf_date_" + suffix, java.time.LocalDate.parse(value).toEpochDay())); }
                    catch (java.time.format.DateTimeParseException ignored) { }
                }
            }
        }
    }

    private static void addSpaceSeparatedIds(Document doc, String field, String raw) {
        String value = raw == null ? "" : raw;
        int start = -1;
        for (int i = 0; i <= value.length(); i++) {
            boolean separator = i == value.length() || Character.isWhitespace(value.charAt(i));
            if (!separator) {
                if (start < 0) start = i;
                continue;
            }
            if (start >= 0) {
                doc.add(new StringField(field, value.substring(start, i), Field.Store.NO));
                start = -1;
            }
        }
    }

    private static void addDelimitedExactValues(Document doc, String field, String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String value : raw.split(",")) {
            String normalized = normalizedExact(value);
            if (!normalized.isEmpty()) doc.add(new StringField(field, normalized, Field.Store.NO));
        }
    }

    private static String normalizedExact(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String formatYear(int year) {
        if (year <= 0) return "0000";
        if (year < 10) return "000" + year;
        if (year < 100) return "00" + year;
        if (year < 1000) return "0" + year;
        return Integer.toString(year);
    }

    private String detectFormat(String fileName) {
        return SupportedFormatRegistry.standard().searchFormat(fileName).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
