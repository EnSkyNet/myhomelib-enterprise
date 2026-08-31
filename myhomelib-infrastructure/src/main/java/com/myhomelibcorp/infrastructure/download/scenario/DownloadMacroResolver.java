package com.myhomelibcorp.infrastructure.download.scenario;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic literal macro expansion. Values are data only and are never re-parsed as commands/macros. */
@Slf4j
public final class DownloadMacroResolver {
    private static final Pattern MACRO = Pattern.compile("%[A-Za-z0-9_]+?%");
    private final Map<String, String> values = new LinkedHashMap<>();

    public DownloadMacroResolver(BookDto book, Collection collection, Path root, String relative, String password) {
        String file = nvl(book.getFileName());
        String ext = extension(file);
        String rootText = unix(root == null ? "" : root.toString());
        String folder = unix(book.getFolder());

        // ===== СПОЧАТКУ ДОДАЄМО %b% ЩОБ ВІН НЕ БУВ ПЕРЕЗАПИСАНИЙ =====
        String firstLetter = extractFirstLetter(book);
        log.debug("DownloadMacroResolver: firstLetter = '{}'", firstLetter);

        // Додаємо з різними варіантами написання
        values.put("%b%", firstLetter);
        values.put("%B%", firstLetter);
        values.put("b", firstLetter); // без відсотків (на всяк випадок)
        log.debug("DownloadMacroResolver: %b% = '{}', %B% = '{}' for book '{}'", firstLetter, firstLetter, book.getTitle());

        // Collection macros from MyHomeLib.
        values.put("%URL%", nvl(collection.getUrl()));
        values.put("%USER%", nvl(collection.getUser()));
        values.put("%PASS%", password); // RAM only; never persisted/logged by the scenario layer.

        // Stable aliases required by the v7.1 contract.
        values.put("%ID%", nvl(book.getId()));
        values.put("%FILE%", file);
        values.put("%FILENAME%", file);
        values.put("%FOLDER%", folder);
        values.put("%ARCHIVE%", unix(relative));
        values.put("%ARCHIVEENTRY%", unix(book.getArchiveEntry()));
        values.put("%EXT%", ext);
        values.put("%COLLECTIONROOT%", rootText);

        // Field-for-field compatibility
        values.put("%TITLE%", nvl(book.getTitle()));
        values.put("%SERIES%", nvl(book.getSeries()));
        values.put("%COLLECTIONNAME%", nvl(collection.getName()));
        values.put("%LANG%", nvl(book.getLanguage()));
        values.put("%SIZE%", String.valueOf(book.getFileSize()));
        values.put("%RATE%", String.valueOf(book.getRate()));
        values.put("%SEQNUMBER%", String.valueOf(book.getSequenceNumber()));
        values.put("%PROGRESS%", String.valueOf(book.getProgress()));
        values.put("%LIBRATE%", String.valueOf(book.getLibraryRate()));
        values.put("%FILEEXT%", ext);
        values.put("%LIBID%", nvl(book.getLibId()));
        values.put("%INSIDENO%", "0");
        values.put("%KEYWORDS%", nvl(book.getKeywords()));
        values.put("%ANNOTATION%", nvl(book.getAnnotation()));
        values.put("%REVIEW%", nvl(book.getReview()));
        values.put("%TRANSLATORS%", nvl(book.getTranslators()));
        values.put("%PUBLISHER%", nvl(book.getPublisher()));
        values.put("%CITY%", nvl(book.getCity()));
        values.put("%PUBYEAR%", nvl(book.getYear()));
        values.put("%ISBN%", nvl(book.getIsbn()));

        // Java-specific useful aliases
        values.put("%YEAR%", nvl(book.getYear()));
        values.put("%SOURCEURL%", nvl(book.getSourceUrl()));
        values.put("%AUTHORSTEXT%", nvl(book.getAuthorsText()));
        values.put("%GENRESTEXT%", nvl(book.getGenresText()));
        values.put("%UPDATEDATE%", book.getUpdateDate() == null ? "" : book.getUpdateDate().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        log.debug("DownloadMacroResolver macros count: {}", values.size());
    }

    public String expand(String template, String responseUrl) throws DownloadScenarioException {
        if (template == null) return null;

        log.debug("expand() template: {}", template);

        // ===== ЯВНА ЗАМІНА ДЛЯ %b% ТА %B% =====
        // Це вирішує проблему, коли регулярний вираз не знаходить %b%
        String result = template;
        String firstLetter = values.get("%b%");
        if (firstLetter == null) {
            firstLetter = values.get("%B%");
        }
        if (firstLetter != null && !firstLetter.isBlank()) {
            result = result.replace("%b%", firstLetter);
            result = result.replace("%B%", firstLetter);
            log.debug("expand(): manually replaced %b% with '{}'", firstLetter);
        }

        // ===== ЗВИЧАЙНА ЗАМІНА ДЛЯ ІНШИХ МАКРОСІВ =====
        Map<String, String> current = new LinkedHashMap<>(values);
        current.put("%RESURL%", nvl(responseUrl));

        Matcher matcher = MACRO.matcher(result);
        StringBuilder out = new StringBuilder(result.length() + 64);
        int last = 0;
        while (matcher.find()) {
            String macro = matcher.group();
            String replacement = current.get(macro);
            if (replacement == null) {
                // Спроба знайти макрос без відсотків
                String key = macro.replace("%", "");
                replacement = current.get(key);
                if (replacement == null) {
                    replacement = macro;
                }
            }
            log.debug("  macro: {} -> '{}'", macro, replacement != null && !replacement.equals(macro) ? replacement : "[NOT FOUND]");
            out.append(result, last, matcher.start());
            out.append(replacement == null ? macro : replacement);
            last = matcher.end();
        }
        out.append(result, last, result.length());
        result = out.toString();

        log.debug("expand() final result: {}", result);

        if (result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0 || result.indexOf('\0') >= 0) {
            throw new DownloadScenarioException("ConnectionScript macro expansion contains forbidden control characters");
        }
        return result;
    }

    /**
     * Видобуває першу літеру імені автора для макроса %b%.
     */
    private static String extractFirstLetter(BookDto book) {
        if (book == null) {
            log.debug("extractFirstLetter: book is null, using '_'");
            return "_";
        }

        String authorName = book.getAuthorsText();
        log.debug("extractFirstLetter: authorsText='{}'", authorName);

        if (authorName == null || authorName.isBlank()) {
            var authors = book.getAuthors();
            if (authors != null && !authors.isEmpty()) {
                var firstAuthor = authors.get(0);
                String fullName = firstAuthor.getFullName();
                if (fullName != null && !fullName.isBlank()) {
                    authorName = fullName;
                } else {
                    String lastName = firstAuthor.getLastName();
                    if (lastName != null && !lastName.isBlank()) {
                        authorName = lastName;
                    }
                }
            }
        }

        if (authorName == null || authorName.isBlank()) {
            log.debug("extractFirstLetter: no author name found, using '_'");
            return "_";
        }

        // Очищуємо від зайвих пробілів
        authorName = authorName.trim();

        // Беремо перший символ, який є літерою
        for (int i = 0; i < authorName.length(); i++) {
            char c = authorName.charAt(i);
            if (Character.isLetter(c)) {
                String result = String.valueOf(c);
                log.debug("extractFirstLetter: first letter = '{}'", result);
                return result;
            }
        }

        log.debug("extractFirstLetter: no letter found, using '_'");
        return "_";
    }

    private static String nvl(Object v) { return v == null ? "" : String.valueOf(v); }
    private static String unix(String value) { return value == null ? "" : value.replace('\\', '/'); }
    private static String extension(String file) {
        int dot = file.lastIndexOf('.');
        return dot < 0 || dot == file.length() - 1 ? "" : file.substring(dot + 1);
    }
}