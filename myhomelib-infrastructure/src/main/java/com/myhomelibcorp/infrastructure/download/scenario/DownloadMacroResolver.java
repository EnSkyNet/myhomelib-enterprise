package com.myhomelibcorp.infrastructure.download.scenario;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.collection.Collection;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic literal macro expansion. Values are data only and are never re-parsed as commands/macros. */
public final class DownloadMacroResolver {
    private static final Pattern MACRO = Pattern.compile("%[A-Za-z0-9_]+%");
    private final Map<String, String> values = new LinkedHashMap<>();

    public DownloadMacroResolver(BookDto book, Collection collection, Path root, String relative, String password) {
        String file = nvl(book.getFileName());
        String ext = extension(file);
        String rootText = unix(root == null ? "" : root.toString());
        String folder = unix(book.getFolder());

        // Collection macros from MyHomeLib.
        put("%URL%", collection.getUrl());
        put("%USER%", collection.getUser());
        put("%PASS%", password); // RAM only; never persisted/logged by the scenario layer.

        // Stable aliases required by the v7.1 contract.
        put("%ID%", book.getId());
        put("%FILE%", file);
        put("%FILENAME%", file);
        put("%FOLDER%", folder);
        put("%ARCHIVE%", unix(relative));
        put("%ARCHIVEENTRY%", unix(book.getArchiveEntry()));
        put("%EXT%", ext);
        put("%COLLECTIONROOT%", rootText);

        // Field-for-field compatibility with string/integer fields exposed by upstream TBookRecord RTTI.
        put("%TITLE%", book.getTitle());
        put("%SERIES%", book.getSeries());
        put("%COLLECTIONNAME%", collection.getName());
        put("%LANG%", book.getLanguage());
        put("%SIZE%", book.getFileSize());
        put("%RATE%", book.getRate());
        put("%SEQNUMBER%", book.getSequenceNumber());
        put("%PROGRESS%", book.getProgress());
        put("%LIBRATE%", book.getLibraryRate());
        put("%FILEEXT%", ext);
        put("%LIBID%", book.getLibId());
        put("%INSIDENO%", 0); // Java model stores archiveEntry rather than upstream numeric InsideNo.
        put("%KEYWORDS%", book.getKeywords());
        put("%ANNOTATION%", book.getAnnotation());
        put("%REVIEW%", book.getReview());
        put("%TRANSLATORS%", book.getTranslators());
        put("%PUBLISHER%", book.getPublisher());
        put("%CITY%", book.getCity());
        put("%PUBYEAR%", book.getYear());
        put("%ISBN%", book.getIsbn());

        // Java-specific useful aliases; they remain literal data macros.
        put("%YEAR%", book.getYear());
        put("%SOURCEURL%", book.getSourceUrl());
        put("%AUTHORSTEXT%", book.getAuthorsText());
        put("%GENRESTEXT%", book.getGenresText());
        put("%UPDATEDATE%", book.getUpdateDate() == null ? "" : book.getUpdateDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    public String expand(String template, String responseUrl) throws DownloadScenarioException {
        if (template == null) return null;
        Map<String, String> current = new LinkedHashMap<>(values);
        current.put("%RESURL%", nvl(responseUrl));

        // Match tokens from the original template exactly once. Replacement values are never scanned again,
        // so e.g. a password containing "%URL%" cannot trigger nested macro evaluation.
        Matcher matcher = MACRO.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        int last = 0;
        while (matcher.find()) {
            out.append(template, last, matcher.start());
            String replacement = current.get(matcher.group());
            out.append(replacement == null ? matcher.group() : replacement);
            last = matcher.end();
        }
        out.append(template, last, template.length());
        String result = out.toString();
        if (result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0 || result.indexOf('\0') >= 0) {
            throw new DownloadScenarioException("ConnectionScript macro expansion contains forbidden control characters");
        }
        return result;
    }

    private void put(String key, Object value) { values.put(key, nvl(value)); }
    private static String nvl(Object v) { return v == null ? "" : String.valueOf(v); }
    private static String unix(String value) { return value == null ? "" : value.replace('\\', '/'); }
    private static String extension(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 || dot == file.length() - 1 ? "" : file.substring(dot + 1);
    }
}
