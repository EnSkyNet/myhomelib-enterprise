package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.application.catalog.CatalogBookSnapshot;
import com.myhomelibcorp.application.catalog.LegacyOnlineBookLocation;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.author.AuthorNameKey;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.Isbn;
import com.myhomelibcorp.domain.service.LanguageResolver;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.BookDenormalizedValues;
import com.myhomelibcorp.shared.util.Sha256Support;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Converts raw INPX records into the explicit JDBC row contract plus catalog/search fingerprints. */
@Slf4j
final class InpxBookNormalizer {
    static final String WITHOUT_AUTHOR_NAME = "Без автора";

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Map<AuthorNameKey, String> authorCache;
    private final Map<String, String> genreCache;

    InpxBookNormalizer(Map<AuthorNameKey, String> authorCache, Map<String, String> genreCache) {
        this.authorCache = authorCache;
        this.genreCache = genreCache;
    }

    NormalizedBook normalize(InpxRecord raw,
                             Map<AuthorNameKey, Author> pendingAuthors,
                             Map<String, Genre> pendingGenres,
                             Path root,
                             Map<String, Boolean> localCache,
                             String sourceMarker,
                             boolean onlineCollection) {
        try {
            ParsedAuthors parsedAuthors = parseAuthors(raw.field("AUTHOR"), pendingAuthors);
            boolean withoutAuthor = parsedAuthors.ids().isEmpty();
            if (withoutAuthor) parsedAuthors = ensureUnknownAuthor(pendingAuthors);
            List<String> genreCodes = parseGenres(raw.field("GENRE"), pendingGenres);
            boolean withoutGenre = genreCodes.isEmpty();

            String title = defaultIfBlank(raw.field("TITLE"), "Без назви");
            String series = raw.field("SERIES");
            int seq = parseInt(raw.field("SERNO"), 0);
            long size = parseLong(raw.field("SIZE"), 0L);
            String ext = normalizeExt(raw.field("EXT"));
            String fileName = defaultIfBlank(raw.field("FILE"), "unknown");
            if (!ext.isBlank() && !fileName.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT))) {
                fileName += "." + ext;
            }

            String archiveName = normalizeRelative(raw.archiveName());
            String explicitFolder = normalizeRelative(raw.field("FOLDER"));
            String folder;
            String archiveEntry;
            boolean local;
            if (onlineCollection && "fb2".equalsIgnoreCase(ext)) {
                // Upstream MyHomeLib online FB2 semantics: catalog package names such as
                // online.zip/extra.zip are never the physical archive of every book.
                // Each book gets its own generated archive location.
                AuthorNameKey primary = parsedAuthors.keys().isEmpty()
                        ? new AuthorNameKey("", "", WITHOUT_AUTHOR_NAME)
                        : parsedAuthors.keys().get(0);
                String authorFullName = String.join(" ",
                        java.util.List.of(primary.lastName(), primary.firstName(), primary.middleName()).stream()
                                .filter(v -> v != null && !v.isBlank()).toList());
                folder = LegacyOnlineBookLocation.archivePath(authorFullName, title, raw.field("LIBID"), fileName);
                archiveEntry = fileName;
                Path archivePath = root.resolve(folder).normalize();
                local = archivePath.startsWith(root)
                        && localCache.computeIfAbsent(folder, key -> Files.isRegularFile(archivePath));
            } else if (!archiveName.isBlank()) {
                folder = archiveName;
                archiveEntry = fileName;
                Path archivePath = root.resolve(archiveName).normalize();
                local = archivePath.startsWith(root)
                        && localCache.computeIfAbsent(archiveName, key -> Files.isRegularFile(archivePath));
            } else {
                folder = explicitFolder;
                archiveEntry = "";
                Path loosePath = explicitFolder.isBlank()
                        ? root.resolve(fileName)
                        : root.resolve(explicitFolder).resolve(fileName);
                loosePath = loosePath.normalize();
                local = loosePath.startsWith(root) && Files.isRegularFile(loosePath);
            }

            String language = LanguageResolver.resolveValue(raw.field("LANG"));
            String keywords = raw.field("KEYWORDS");
            String annotation = raw.field("ANNOTATION");
            boolean deleted = parseDeleted(raw.field("DEL"));
            String libId = raw.field("LIBID");
            String identity = !libId.isBlank()
                    ? "inpx:libid:" + libId
                    : sourceMarker + ":" + archiveName + ":" + fileName;
            String bookId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
            String now = LocalDateTime.now().format(DATE_FORMATTER);

            int year = parseYear(firstNonBlank(raw.field("YEAR"), raw.field("PUBYEAR"), raw.field("DATE")));
            String publisher = firstNonBlank(raw.field("PUBLISHER"), raw.field("PUBLISH"));
            int libraryRate = parseInt(firstNonBlank(raw.field("LIBRATE"), raw.field("LIBRARYRATE")), 0);
            String translators = firstNonBlank(raw.field("TRANSLATORS"), raw.field("TRANSLATOR"));
            String city = raw.field("CITY");

            Object[] row = new Object[30];
            int i = 0;
            row[i++] = bookId;
            row[i++] = title;
            row[i++] = series;
            row[i++] = seq;
            row[i++] = fileName;
            row[i++] = folder;
            row[i++] = archiveEntry;
            row[i++] = language;
            row[i++] = size;
            row[i++] = keywords;
            row[i++] = annotation;
            row[i++] = 0; // user rate is preserved by UPSERT
            row[i++] = 0; // progress is preserved by UPSERT
            row[i++] = now;
            row[i++] = Isbn.tryParse(raw.field("ISBN")).map(Isbn::value).orElse(null);
            row[i++] = deleted ? 1 : 0;
            row[i++] = local ? 1 : 0;
            row[i++] = ""; // review is preserved by UPSERT
            row[i++] = now; // created_at is preserved by UPSERT
            row[i++] = parsedAuthors.ids();
            row[i++] = List.copyOf(genreCodes);
            row[i++] = root.toString();
            row[i++] = year > 0 ? year : null;
            row[i++] = publisher;
            row[i++] = libId;
            row[i++] = libraryRate;
            row[i++] = translators;
            row[i++] = city;
            row[i++] = sourceMarker;
            row[i] = BookDenormalizedValues.authorSort(parsedAuthors.keys());

            String sourceBookKey = !libId.isBlank() ? "libid:" + libId : archiveName + ":" + fileName;
            CatalogBookSnapshot catalogSnapshot = new CatalogBookSnapshot(
                    bookId, sourceBookKey, catalogFingerprint(raw, archiveName, explicitFolder, fileName),
                    fileName, !archiveName.isBlank() ? archiveName : explicitFolder,
                    !archiveName.isBlank() ? fileName : "", size);
            return new NormalizedBook(row, catalogSnapshot, searchableFingerprint(raw, fileName, deleted),
                    withoutAuthor, withoutGenre, deleted);
        } catch (Exception e) {
            log.warn("Skipping malformed INPX record {} from {}", raw.field("FILE"), raw.inpName(), e);
            return null;
        }
    }

    static String sourceMarker(Path sourceInpx, Path root) {
        Path source = sourceInpx.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String stablePath;
        try {
            stablePath = source.startsWith(normalizedRoot)
                    ? normalizeRelative(normalizedRoot.relativize(source).toString())
                    : source.toString().replace('\\', '/');
        } catch (RuntimeException ignored) {
            stablePath = source.getFileName() == null ? source.toString() : source.getFileName().toString();
        }
        return "inpx:" + stablePath;
    }

    private ParsedAuthors parseAuthors(String value, Map<AuthorNameKey, Author> pending) {
        List<String> ids = new ArrayList<>(2);
        List<AuthorNameKey> keys = new ArrayList<>(2);
        if (value == null || value.isBlank() || value.equals(":")) return new ParsedAuthors(ids, keys);
        for (String item : value.split(":")) {
            String[] parts = item.trim().split(",", -1);
            String last = parts.length > 0 ? parts[0].trim() : "";
            String first = parts.length > 1 ? parts[1].trim() : "";
            String middle = parts.length > 2 ? parts[2].trim() : "";
            if (last.isBlank() && first.isBlank() && middle.isBlank()) continue;
            AuthorNameKey key = new AuthorNameKey(first, middle, last);
            keys.add(key);
            String existingId = authorCache.get(key);
            if (existingId != null) {
                ids.add(existingId);
                continue;
            }
            Author candidate = pending.computeIfAbsent(key, ignored -> new Author(first, middle, last));
            ids.add(candidate.getId().asString());
        }
        return new ParsedAuthors(ids, keys);
    }

    private ParsedAuthors ensureUnknownAuthor(Map<AuthorNameKey, Author> pending) {
        AuthorNameKey key = new AuthorNameKey("", "", WITHOUT_AUTHOR_NAME);
        String existingId = authorCache.get(key);
        if (existingId != null) return new ParsedAuthors(List.of(existingId), List.of(key));
        Author candidate = pending.computeIfAbsent(key, ignored -> new Author("", "", WITHOUT_AUTHOR_NAME));
        return new ParsedAuthors(List.of(candidate.getId().asString()), List.of(key));
    }

    private List<String> parseGenres(String value, Map<String, Genre> pending) {
        List<String> result = new ArrayList<>(2);
        if (value == null || value.isBlank()) return result;
        for (String item : value.split(":")) {
            String code = item.trim();
            if (code.isBlank()) continue;
            if (!genreCache.containsKey(code) && !pending.containsKey(code)) pending.put(code, new Genre(code, code));
            result.add(code);
        }
        return result;
    }

    private static String searchableFingerprint(InpxRecord raw, String resolvedFileName, boolean deleted) {
        MessageDigest digest = Sha256Support.newDigest();
        for (String field : List.of("TITLE", "AUTHOR", "GENRE", "SERIES", "LANG", "KEYWORDS", "ANNOTATION",
                "PUBLISHER", "PUBLISH", "TRANSLATORS", "TRANSLATOR", "CITY", "LIBID", "LIBRATE",
                "LIBRARYRATE", "YEAR", "PUBYEAR", "ISBN")) {
            Sha256Support.updateLengthPrefixedUtf8(digest, field + "=" + safe(raw.field(field)));
        }
        Sha256Support.updateLengthPrefixedUtf8(digest, "FILE=" + safe(resolvedFileName));
        Sha256Support.updateLengthPrefixedUtf8(digest, "DELETED=" + deleted);
        return Sha256Support.finish(digest);
    }

    private static String catalogFingerprint(InpxRecord raw, String archiveName,
                                             String explicitFolder, String resolvedFileName) {
        MessageDigest digest = Sha256Support.newDigest();
        raw.fields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Sha256Support.updateLengthPrefixedUtf8(digest, entry.getKey());
                    Sha256Support.updateLengthPrefixedUtf8(digest, entry.getValue());
                });
        Sha256Support.updateLengthPrefixedUtf8(digest, "ARCHIVE=" + safe(archiveName));
        Sha256Support.updateLengthPrefixedUtf8(digest, "FOLDER=" + safe(explicitFolder));
        Sha256Support.updateLengthPrefixedUtf8(digest, "RESOLVED_FILE=" + safe(resolvedFileName));
        return Sha256Support.finish(digest);
    }

    private static String normalizeExt(String value) {
        String normalized = safe(value).trim();
        while (normalized.startsWith(".")) normalized = normalized.substring(1);
        return normalized;
    }

    private static String normalizeRelative(String value) {
        String normalized = safe(value).replace('\\', '/').trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.contains("../") || normalized.equals("..")) return "";
        return normalized;
    }

    private static boolean parseDeleted(String value) {
        String normalized = safe(value).trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1") || normalized.equals("true")
                || normalized.equals("yes") || normalized.equals("deleted");
    }

    private static int parseYear(String value) {
        String normalized = safe(value).trim();
        var matcher = java.util.regex.Pattern.compile("(?<!\\d)(18|19|20|21)\\d{2}(?!\\d)").matcher(normalized);
        return matcher.find() ? parseInt(matcher.group(), 0) : 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static long parseLong(String value, long fallback) {
        try { return Long.parseLong(value.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record ParsedAuthors(List<String> ids, List<AuthorNameKey> keys) {
        private ParsedAuthors {
            ids = List.copyOf(ids);
            keys = List.copyOf(keys);
        }
    }

    record NormalizedBook(Object[] row, CatalogBookSnapshot catalogSnapshot, String searchFingerprint,
                          boolean withoutAuthor, boolean withoutGenre, boolean explicitlyDeleted) { }
}
