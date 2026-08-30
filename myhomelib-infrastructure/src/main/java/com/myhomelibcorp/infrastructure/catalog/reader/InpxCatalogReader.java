package com.myhomelibcorp.infrastructure.catalog.reader;

import com.myhomelibcorp.application.catalog.importing.*;
import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;
import com.myhomelibcorp.application.imports.diagnostics.ImportSeverity;
import com.myhomelibcorp.domain.model.valueobject.Isbn;
import com.myhomelibcorp.domain.service.LanguageResolver;
import com.myhomelibcorp.infrastructure.importengine.InpxReader;
import com.myhomelibcorp.infrastructure.importengine.InpxRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;

/** Source-neutral streaming adapter over the existing robust INPX reader. */
@Component
@RequiredArgsConstructor
public class InpxCatalogReader implements CatalogReader {
    private final InpxReader inpxReader;

    @Override
    public boolean supports(Path source) {
        if (source == null || source.getFileName() == null) return false;
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".inpx") || name.endsWith(".inp");
    }

    @Override
    public CatalogReadSession open(Path source) {
        Iterator<InpxRecord> records = inpxReader.read(source);
        return new Session(records, source);
    }

    @Override public String formatName() { return "INPX"; }

    private static final class Session implements CatalogReadSession {
        private final Iterator<InpxRecord> delegate;
        private final CatalogDatasetInfo dataset;

        private Session(Iterator<InpxRecord> delegate, Path source) {
            this.delegate = delegate;
            this.dataset = new CatalogDatasetInfo(
                    "myhomelib.inpx/1", "myhomelib.inp_record/1",
                    source.toAbsolutePath().normalize().toString(), "", null,
                    Map.of("source", source.toAbsolutePath().normalize().toString()));
        }

        @Override public CatalogDatasetInfo dataset() { return dataset; }
        @Override public boolean hasNext() { return delegate.hasNext(); }
        @Override public CatalogRecord next() { return map(delegate.next()); }

        @Override
        public void close() {
            if (delegate instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception e) {
                    throw new IllegalStateException("Cannot close INPX reader", e);
                }
            }
        }
    }

    private static CatalogRecord map(InpxRecord raw) {
        String libId = raw.field("LIBID");
        String file = raw.field("FILE");
        String ext = normalizeExt(raw.field("EXT"));
        String fileName = file;
        if (!ext.isBlank() && !file.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT))) {
            fileName = file + "." + ext;
        }
        String sourceId = !libId.isBlank() ? "libid:" + libId
                : raw.archiveName() + ":" + fileName;

        List<ImportIssue> issues = new ArrayList<>();
        String rawIsbn = raw.field("ISBN");
        String isbn = Isbn.tryParse(rawIsbn).map(Isbn::value).orElse("");
        if (!rawIsbn.isBlank() && isbn.isBlank()) {
            issues.add(new ImportIssue(ImportSeverity.WARNING, "normalize", "INVALID_ISBN",
                    sourceId, "Invalid ISBN ignored", false, Map.of("value", rawIsbn)));
        }

        List<CatalogPerson> authors = parsePeople(raw.field("AUTHOR"));
        if (authors.isEmpty()) authors = List.of(new CatalogPerson("", "", "Невідомий Автор", "", "", "", List.of()));

        List<String> genres = splitColon(raw.field("GENRE"));
        String series = raw.field("SERIES");
        Double sequence = parseDouble(raw.field("SERNO"));
        long size = parseLong(raw.field("SIZE"), 0L);
        boolean deleted = parseDeleted(raw.field("DEL"));
        Integer year = parseYear(firstNonBlank(raw.field("YEAR"), raw.field("PUBYEAR"), raw.field("DATE")));
        String publisher = firstNonBlank(raw.field("PUBLISHER"), raw.field("PUBLISH"));
        String translators = firstNonBlank(raw.field("TRANSLATORS"), raw.field("TRANSLATOR"));

        CatalogArtifact artifact = new CatalogArtifact(
                fileName, mediaType(ext), ext, raw.archiveName(), raw.archiveName().isBlank() ? "" : fileName,
                size, "", "", Map.of("inp", raw.inpName()));

        List<ExternalIdentity> identities = libId.isBlank()
                ? List.of() : List.of(new ExternalIdentity("inpx:libid", libId));

        return new CatalogRecord(
                sourceId,
                firstNonBlank(raw.field("TITLE"), "Без назви"),
                authors,
                series,
                sequence,
                genres,
                LanguageResolver.resolveValue(raw.field("LANG")),
                ext,
                fileName,
                raw.archiveName(),
                raw.archiveName().isBlank() ? "" : fileName,
                size,
                deleted,
                isbn,
                publisher,
                year,
                raw.field("CITY"),
                parsePeople(translators),
                raw.field("ANNOTATION"),
                splitKeywords(raw.field("KEYWORDS")),
                parseDouble(firstNonBlank(raw.field("LIBRATE"), raw.field("LIBRARYRATE"))),
                Map.of("inp", raw.inpName(), "archive", raw.archiveName(), "libId", libId),
                List.of(artifact),
                identities,
                issues);
    }

    private static List<CatalogPerson> parsePeople(String value) {
        if (value == null || value.isBlank() || value.equals(":")) return List.of();
        List<CatalogPerson> out = new ArrayList<>();
        for (String item : value.split(":")) {
            if (item.isBlank()) continue;
            String[] p = item.trim().split(",", -1);
            String last = p.length > 0 ? p[0].trim() : "";
            String first = p.length > 1 ? p[1].trim() : "";
            String middle = p.length > 2 ? p[2].trim() : "";
            if (!first.isBlank() || !middle.isBlank() || !last.isBlank()) {
                out.add(new CatalogPerson(first, middle, last, "", "", "", List.of()));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> splitColon(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(":")) .map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static List<String> splitKeywords(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("[,;]")) .map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private static String normalizeExt(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        while (v.startsWith(".")) v = v.substring(1);
        return v;
    }

    private static String mediaType(String ext) {
        return switch (ext) {
            case "fb2" -> "application/x-fictionbook+xml";
            case "epub" -> "application/epub+zip";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            default -> "application/octet-stream";
        };
    }

    private static boolean parseDeleted(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("deleted");
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value.trim()); } catch (Exception e) { return fallback; }
    }
    private static Double parseDouble(String value) {
        try { return value == null || value.isBlank() ? null : Double.parseDouble(value.trim()); } catch (Exception e) { return null; }
    }
    private static Integer parseYear(String value) {
        if (value == null) return null;
        var m = java.util.regex.Pattern.compile("(?<!\\d)(18|19|20|21)\\d{2}(?!\\d)").matcher(value);
        return m.find() ? Integer.valueOf(m.group()) : null;
    }
    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }
}
