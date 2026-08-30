package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.domain.model.book.BookSnapshot;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Maps immutable catalogue snapshots to the Lucene document schema. */
final class LuceneDocumentMapper {
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
        doc.add(new TextField("translators", safe(snapshot.getTranslators()), Field.Store.NO));
        doc.add(new TextField("city", safe(snapshot.getCity()), Field.Store.NO));
        doc.add(new StringField("lib_id", safe(snapshot.getLibId()), Field.Store.NO));
        doc.add(new TextField("isbn", safe(snapshot.getIsbn()), Field.Store.NO));
        doc.add(new TextField("source", safe(snapshot.getSourceUrl()), Field.Store.NO));
        doc.add(new StringField("language", safe(snapshot.getLanguage()).toLowerCase(Locale.ROOT), Field.Store.NO));
        for (String id : safe(snapshot.getAuthorIds()).split("\\s+")) if (!id.isBlank()) doc.add(new StringField("author_id", id, Field.Store.NO));
        for (String id : safe(snapshot.getGenreIds()).split("\\s+")) if (!id.isBlank()) doc.add(new StringField("genre_id", id, Field.Store.NO));

        int libraryRate = snapshot.getLibraryRate() == null ? 0 : snapshot.getLibraryRate();
        doc.add(new IntPoint("library_rate_num", libraryRate));
        doc.add(new StringField("library_rate", Integer.toString(libraryRate), Field.Store.NO));
        int rate = snapshot.getRate() == null ? 0 : snapshot.getRate();
        doc.add(new IntPoint("rate_num", rate));
        doc.add(new StringField("rate", Integer.toString(rate), Field.Store.NO));
        int progress = snapshot.getProgress() == null ? 0 : snapshot.getProgress();
        doc.add(new IntPoint("progress_num", progress));
        doc.add(new StringField("read", progress >= 100 ? "1" : "0", Field.Store.NO));
        doc.add(new StringField("format", detectFormat(snapshot.getFileName()), Field.Store.NO));
        int year = snapshot.getYear() == null ? 0 : snapshot.getYear();
        doc.add(new IntPoint("year_num", year));
        doc.add(new StringField("year", year <= 0 ? "0000" : String.format(Locale.ROOT, "%04d", year), Field.Store.NO));
        if (snapshot.getCreatedAt() != null) {
            doc.add(new LongPoint("created_day", snapshot.getCreatedAt().toLocalDate().toEpochDay()));
            doc.add(new StringField("created", snapshot.getCreatedAt().toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE), Field.Store.NO));
        }
        doc.add(new StringField("local", snapshot.isLocal() ? "1" : "0", Field.Store.NO));
        doc.add(new StringField("deleted", snapshot.isDeleted() ? "1" : "0", Field.Store.NO));
        return doc;
    }

    private String detectFormat(String fileName) {
        String name = safe(fileName).toLowerCase(Locale.ROOT);
        if (name.endsWith(".fb2.zip")) return "fb2zip";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "unknown";
        String ext = name.substring(dot + 1);
        return switch (ext) {
            case "fb2", "epub", "pdf", "mobi", "inpx", "zip" -> ext;
            default -> "unknown";
        };
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
