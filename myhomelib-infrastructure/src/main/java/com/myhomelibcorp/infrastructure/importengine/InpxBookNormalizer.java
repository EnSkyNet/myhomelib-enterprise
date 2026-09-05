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
import java.util.Set;
import java.util.regex.Pattern;

/** Converts raw INPX records into the explicit JDBC row contract plus catalog/search fingerprints. */
@Slf4j
final class InpxBookNormalizer {
    static final String WITHOUT_AUTHOR_NAME = "Без автора";

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(?<!\\d)(18|19|20|21)\\d{2}(?!\\d)");

    private final Map<AuthorNameKey, String> authorCache;
    private final Map<String, String> genreCache;
    private final byte[] catalogLengthPrefix = new byte[Integer.BYTES];
    private List<String> canonicalFieldOrder;

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
        return normalize(raw, pendingAuthors, pendingGenres, root, localCache, sourceMarker, onlineCollection, null, true);
    }

    NormalizedBook normalize(InpxRecord raw,
                             Map<AuthorNameKey, Author> pendingAuthors,
                             Map<String, Genre> pendingGenres,
                             Path root,
                             Map<String, Boolean> localCache,
                             String sourceMarker,
                             boolean onlineCollection,
                             Set<Path> indexedLocalFiles,
                             boolean includeSearchFingerprint) {
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
                String authorFullName = joinName(primary.lastName(), primary.firstName(), primary.middleName());
                folder = LegacyOnlineBookLocation.archivePath(authorFullName, title, raw.field("LIBID"), fileName);
                archiveEntry = fileName;
                if (indexedLocalFiles != null && indexedLocalFiles.isEmpty()) {
                    // A pre-scan already proved that no local book archives exist. Avoid creating a
                    // platform Path from catalog text: besides saving work, this keeps remote-only
                    // imports independent of the host native filename encoding.
                    local = false;
                } else {
                    Path archivePath = root.resolve(folder).toAbsolutePath().normalize();
                    local = archivePath.startsWith(root)
                            && (indexedLocalFiles != null
                                ? indexedLocalFiles.contains(archivePath)
                                : localCache.computeIfAbsent(folder, key -> Files.isRegularFile(archivePath)));
                }
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
            String searchFingerprint = includeSearchFingerprint
                    ? searchableFingerprint(raw, fileName, deleted)
                    : null;
            return new NormalizedBook(row, catalogSnapshot, searchFingerprint,
                    withoutAuthor, withoutGenre, deleted);
        } catch (Exception e) {
            log.warn("Skipping malformed INPX record {} from {}", raw.field("FILE"), raw.inpName(), e);
            return null;
        }
    }

    CatalogPreview preview(InpxRecord raw, String sourceMarker) {
        try {
            String ext = normalizeExt(raw.field("EXT"));
            String fileName = defaultIfBlank(raw.field("FILE"), "unknown");
            if (!ext.isBlank() && !fileName.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT))) {
                fileName += "." + ext;
            }
            String archiveName = normalizeRelative(raw.archiveName());
            String explicitFolder = normalizeRelative(raw.field("FOLDER"));
            long size = parseLong(raw.field("SIZE"), 0L);
            boolean deleted = parseDeleted(raw.field("DEL"));
            String libId = raw.field("LIBID");
            String identity = !libId.isBlank()
                    ? "inpx:libid:" + libId
                    : sourceMarker + ":" + archiveName + ":" + fileName;
            String bookId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
            String sourceBookKey = !libId.isBlank() ? "libid:" + libId : archiveName + ":" + fileName;
            CatalogBookSnapshot snapshot = new CatalogBookSnapshot(
                    bookId, sourceBookKey, catalogFingerprint(raw, archiveName, explicitFolder, fileName),
                    fileName, !archiveName.isBlank() ? archiveName : explicitFolder,
                    !archiveName.isBlank() ? fileName : "", size);
            return new CatalogPreview(raw, snapshot, fileName, ext, archiveName, explicitFolder,
                    deleted, !hasImportableAuthor(raw.field("AUTHOR")), !hasNonBlankToken(raw.field("GENRE")));
        } catch (Exception e) {
            log.warn("Skipping malformed INPX catalog preview {} from {}", raw.field("FILE"), raw.inpName(), e);
            return null;
        }
    }

    boolean previewLocal(CatalogPreview preview,
                         Path root,
                         Map<String, Boolean> localCache,
                         boolean onlineCollection,
                         Set<Path> indexedLocalFiles) {
        if (preview == null) return false;
        if (onlineCollection && "fb2".equalsIgnoreCase(preview.ext())) {
            if (indexedLocalFiles != null && indexedLocalFiles.isEmpty()) return false;
            AuthorNameKey primary = firstImportableAuthor(preview.raw().field("AUTHOR"));
            if (primary == null) primary = new AuthorNameKey("", "", WITHOUT_AUTHOR_NAME);
            String authorFullName = joinName(primary.lastName(), primary.firstName(), primary.middleName());
            String title = defaultIfBlank(preview.raw().field("TITLE"), "Без назви");
            String folder = LegacyOnlineBookLocation.archivePath(
                    authorFullName, title, preview.raw().field("LIBID"), preview.fileName());
            Path archivePath = root.resolve(folder).toAbsolutePath().normalize();
            return archivePath.startsWith(root)
                    && (indexedLocalFiles != null
                        ? indexedLocalFiles.contains(archivePath)
                        : localCache.computeIfAbsent(folder, key -> Files.isRegularFile(archivePath)));
        }
        if (!preview.archiveName().isBlank()) {
            Path archivePath = root.resolve(preview.archiveName()).normalize();
            return archivePath.startsWith(root)
                    && localCache.computeIfAbsent(preview.archiveName(), key -> Files.isRegularFile(archivePath));
        }
        Path loosePath = preview.explicitFolder().isBlank()
                ? root.resolve(preview.fileName())
                : root.resolve(preview.explicitFolder()).resolve(preview.fileName());
        loosePath = loosePath.normalize();
        return loosePath.startsWith(root) && Files.isRegularFile(loosePath);
    }

    String previewSearchFingerprint(CatalogPreview preview) {
        if (preview == null) return null;
        return searchableFingerprint(preview.raw(), preview.fileName(), preview.deleted());
    }

    private static boolean hasImportableAuthor(String value) {
        return firstImportableAuthor(value) != null;
    }

    private static AuthorNameKey firstImportableAuthor(String value) {
        if (value == null || value.isBlank() || value.equals(":")) return null;
        int start = 0;
        while (start <= value.length()) {
            int end = value.indexOf(':', start);
            if (end < 0) end = value.length();
            String item = value.substring(start, end).trim();
            if (!item.isEmpty()) {
                AuthorNameParts parts = parseAuthorName(item);
                if (!parts.last().isBlank() || !parts.first().isBlank() || !parts.middle().isBlank()) {
                    return new AuthorNameKey(parts.first(), parts.middle(), parts.last());
                }
            }
            if (end == value.length()) break;
            start = end + 1;
        }
        return null;
    }

    private static boolean hasNonBlankToken(String value) {
        if (value == null || value.isBlank()) return false;
        int start = 0;
        while (start <= value.length()) {
            int end = value.indexOf(':', start);
            if (end < 0) end = value.length();
            if (!value.substring(start, end).trim().isBlank()) return true;
            if (end == value.length()) break;
            start = end + 1;
        }
        return false;
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
        int start = 0;
        while (start <= value.length()) {
            int end = value.indexOf(':', start);
            if (end < 0) end = value.length();
            String item = value.substring(start, end).trim();
            if (!item.isEmpty()) {
                AuthorNameParts parts = parseAuthorName(item);
                String last = parts.last();
                String first = parts.first();
                String middle = parts.middle();
                if (!last.isBlank() || !first.isBlank() || !middle.isBlank()) {
                    AuthorNameKey key = new AuthorNameKey(first, middle, last);
                    keys.add(key);
                    String existingId = authorCache.get(key);
                    if (existingId != null) {
                        ids.add(existingId);
                    } else {
                        Author candidate = pending.computeIfAbsent(key, ignored -> new Author(first, middle, last));
                        ids.add(candidate.getId().asString());
                    }
                }
            }
            if (end == value.length()) break;
            start = end + 1;
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
        int start = 0;
        while (start <= value.length()) {
            int end = value.indexOf(':', start);
            if (end < 0) end = value.length();
            String code = value.substring(start, end).trim();
            if (!code.isBlank()) {
                if (!genreCache.containsKey(code) && !pending.containsKey(code)) pending.put(code, new Genre(code, code));
                result.add(code);
            }
            if (end == value.length()) break;
            start = end + 1;
        }
        return result;
    }

    private static AuthorNameParts parseAuthorName(String item) {
        int firstComma = item.indexOf(',');
        if (firstComma < 0) return new AuthorNameParts(item.trim(), "", "");
        String last = item.substring(0, firstComma).trim();
        int secondComma = item.indexOf(',', firstComma + 1);
        if (secondComma < 0) return new AuthorNameParts(last, item.substring(firstComma + 1).trim(), "");
        String first = item.substring(firstComma + 1, secondComma).trim();
        int thirdComma = item.indexOf(',', secondComma + 1);
        String middle = item.substring(secondComma + 1, thirdComma < 0 ? item.length() : thirdComma).trim();
        return new AuthorNameParts(last, first, middle);
    }

    private static String joinName(String... parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(part);
        }
        return result.toString();
    }

    private record AuthorNameParts(String last, String first, String middle) { }

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

    private String catalogFingerprint(InpxRecord raw, String archiveName,
                                      String explicitFolder, String resolvedFileName) {
        MessageDigest digest = Sha256Support.newDigest();
        List<String> order = canonicalFieldOrder;
        if (order == null || order.size() != raw.fields().size() || !raw.fields().keySet().containsAll(order)) {
            order = raw.fields().keySet().stream().sorted().toList();
            canonicalFieldOrder = order;
        }
        for (String key : order) {
            updateCatalogLengthPrefixedUtf8(digest, key);
            updateCatalogLengthPrefixedUtf8(digest, raw.fields().getOrDefault(key, ""));
        }
        updateCatalogLengthPrefixedUtf8(digest, "ARCHIVE=" + safe(archiveName));
        updateCatalogLengthPrefixedUtf8(digest, "FOLDER=" + safe(explicitFolder));
        updateCatalogLengthPrefixedUtf8(digest, "RESOLVED_FILE=" + safe(resolvedFileName));
        return Sha256Support.finish(digest);
    }

    private void updateCatalogLengthPrefixedUtf8(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        catalogLengthPrefix[0] = (byte) ((length >>> 24) & 0xff);
        catalogLengthPrefix[1] = (byte) ((length >>> 16) & 0xff);
        catalogLengthPrefix[2] = (byte) ((length >>> 8) & 0xff);
        catalogLengthPrefix[3] = (byte) (length & 0xff);
        digest.update(catalogLengthPrefix);
        digest.update(bytes);
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
        var matcher = YEAR_PATTERN.matcher(normalized);
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

    record CatalogPreview(InpxRecord raw, CatalogBookSnapshot catalogSnapshot, String fileName, String ext,
                          String archiveName, String explicitFolder, boolean deleted,
                          boolean withoutAuthor, boolean withoutGenre) { }

    record NormalizedBook(Object[] row, CatalogBookSnapshot catalogSnapshot, String searchFingerprint,
                          boolean withoutAuthor, boolean withoutGenre, boolean explicitlyDeleted) { }
}
