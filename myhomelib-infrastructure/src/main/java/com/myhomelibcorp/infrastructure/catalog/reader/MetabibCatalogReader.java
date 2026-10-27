package com.myhomelibcorp.infrastructure.catalog.reader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.luben.zstd.ZstdInputStream;
import com.myhomelibcorp.application.catalog.importing.*;
import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;
import com.myhomelibcorp.application.imports.diagnostics.ImportSeverity;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.valueobject.Isbn;
import com.myhomelibcorp.domain.service.LanguageResolver;
import com.myhomelibcorp.infrastructure.archive.SafeZipArchive;
import com.myhomelibcorp.infrastructure.util.LimitedInputStream;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Native, streaming reader for the public metabib.dataset/1 JSONL format.
 * This is an independent implementation based on the published data schema; no GPL code is copied.
 */
@Component
@RequiredArgsConstructor
public class MetabibCatalogReader implements CatalogReader {
    public static final String DATASET_SCHEMA = "metabib.dataset/1";
    public static final String RECORD_SCHEMA = "metabib.dataset_record/1";
    /** A dataset may be arbitrarily large, but one JSONL object must stay memory-bounded. */
    static final int MAX_JSONL_RECORD_CHARS = 8 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(Path source) {
        if (source == null || source.getFileName() == null) return false;
        final Path safeSource;
        try {
            safeSource = SafeFilePublication.requireSafeRegularFile(source, "Metabib source");
        } catch (IOException unsafe) {
            return false;
        }
        String name = safeSource.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jsonl") || name.endsWith(".jsonl.gz") || name.endsWith(".jsonl.zst")) return true;
        if (!name.endsWith(".zip")) return false;
        // Do not steal legacy INPX update ZIPs from the existing importer: a ZIP is metabib
        // only when it actually contains a JSONL dataset entry. Bound the central-directory scan
        // so a hostile archive cannot turn a lightweight supports() probe into unbounded work.
        try (SafeZipArchive opened = SafeZipArchive.openDetected(safeSource, "Metabib ZIP source")) {
            return selectZipDatasetEntry(opened.zip()) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public CatalogReadSession open(Path source) {
        try {
            if (source == null || source.getFileName() == null) {
                throw new IOException("Metabib source is not a safe regular file: " + source);
            }
            Path safeSource = SafeFilePublication.requireSafeRegularFile(source, "Metabib source");
            OpenedInput opened = openInput(safeSource);
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(opened.stream(), StandardCharsets.UTF_8), 128 * 1024);
                String headerLine = readNonBlank(reader);
                if (headerLine == null) {
                    throw new IllegalArgumentException("Empty metabib dataset: " + source);
                }
                headerLine = stripBom(headerLine);
                JsonNode header = objectMapper.readTree(headerLine);
                MetabibDatasetValidator.Context validation = MetabibDatasetValidator.validateHeader(header, source);
                CatalogDatasetInfo info = new CatalogDatasetInfo(
                        text(header, "schema"), text(header, "record_schema"), text(header, "id"), text(header, "library"),
                        header.has("records") && header.get("records").canConvertToLong() ? header.get("records").asLong() : null,
                        datasetMetadata(header));
                return new Session(reader, opened, info, source, validation);
                } catch (IOException | RuntimeException | Error failure) {
                // Ownership transfers only after Session (including its initial read)
                // has been constructed successfully. Keep the original parse failure.
                try { opened.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open metabib dataset " + source + ": " + e.getMessage(), e);
        }
    }

    @Override public String formatName() { return "metabib.dataset/1"; }

    private final class Session implements CatalogReadSession {
        private final BufferedReader reader;
        private final OpenedInput opened;
        private final CatalogDatasetInfo dataset;
        private final Path source;
        private final MetabibDatasetValidator.Context validation;
        private long recordNumber;
        private CatalogRecord nextRecord;
        private boolean closed;

        private Session(BufferedReader reader, OpenedInput opened, CatalogDatasetInfo dataset, Path source,
                        MetabibDatasetValidator.Context validation) throws IOException {
            this.reader = reader;
            this.opened = opened;
            this.dataset = dataset;
            this.source = source;
            this.validation = validation;
            advance();
        }

        @Override public CatalogDatasetInfo dataset() { return dataset; }
        @Override public boolean hasNext() { return nextRecord != null; }

        @Override
        public CatalogRecord next() {
            if (nextRecord == null) throw new NoSuchElementException();
            CatalogRecord current = nextRecord;
            try {
                advance();
                return current;
            } catch (IOException | RuntimeException e) {
                close();
                throw new IllegalStateException("Malformed metabib dataset near record " + recordNumber + " in " + source
                        + ": " + e.getMessage(), e);
            }
        }

        private void advance() throws IOException {
            nextRecord = null;
            while (true) {
                String line = readNonBlank(reader);
                if (line == null) {
                    validateRecordCount();
                    return;
                }
                recordNumber++;
                if (validation.declaredRecords() != null && recordNumber > validation.declaredRecords()) {
                    throw new IllegalArgumentException("metabib dataset has trailing records: declared="
                            + validation.declaredRecords() + ", actual>=" + recordNumber);
                }
                JsonNode node = objectMapper.readTree(line);
                if (!RECORD_SCHEMA.equals(text(node, "schema"))) {
                    throw new IllegalArgumentException("Invalid metabib record schema at record " + recordNumber
                            + ": " + text(node, "schema"));
                }
                MetabibDatasetValidator.validateRecordStructure(node, validation, recordNumber);
                if (validation.isDummyRecord(node)) continue;
                nextRecord = mapRecord(node, dataset, recordNumber, validation);
                return;
            }
        }

        private void validateRecordCount() {
            if (validation.declaredRecords() != null && recordNumber != validation.declaredRecords()) {
                throw new IllegalArgumentException("metabib record count mismatch: declared="
                        + validation.declaredRecords() + ", actual=" + recordNumber);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            nextRecord = null;
            try { reader.close(); } catch (IOException ignored) { }
            try { opened.close(); } catch (IOException ignored) { }
        }
    }

    private CatalogRecord mapRecord(JsonNode root, CatalogDatasetInfo dataset, long recordNumber, MetabibDatasetValidator.Context validation) {
        JsonNode descriptor = root.path("record");
        JsonNode locator = descriptor.path("locator");
        String recordLibrary = text(descriptor, "library");
        if (recordLibrary.isBlank()) recordLibrary = dataset.library();
        String locatorKind = text(locator, "kind");
        String locatorSource = text(locator, "source");
        String locatorValue = locator.has("book_id") ? Long.toString(locator.path("book_id").asLong())
                : locator.has("index") ? Integer.toString(locator.path("index").asInt())
                : locatorSource;
        // Archive entry indexes are local to each ZIP. Using only "archive_entry:<index>"
        // collapses distinct books from separate archives into the same imported identity.
        // Length-prefix the source to keep the compound locator unambiguous even when
        // archive paths themselves contain colons (or separators).
        String locatorIdentity = "archive_entry".equals(locatorKind)
                ? locatorSource.length() + ":" + locatorSource + ":" + locatorValue
                : locatorValue;
        String sourceBookId = firstNonBlank(recordLibrary, "metabib") + ":" + firstNonBlank(locatorKind, "record") + ":" + locatorIdentity;

        JsonNode claims = root.path("claims");
        JsonNode bibliographic = claims.path("bibliographic");
        JsonNode publication = claims.path("publication");
        JsonNode catalog = claims.path("catalog");

        String title = firstClaimText(bibliographic, "title");
        if (title.isBlank()) title = firstClaimText(publication, "book_name");
        if (title.isBlank()) title = sourceBookId;

        List<CatalogPerson> authors = peopleClaims(bibliographic, "authors", validation, dataset.datasetId());
        List<CatalogPerson> translators = peopleClaims(bibliographic, "translators", validation, dataset.datasetId());

        JsonNode sequence = firstClaimValue(bibliographic, "sequences");
        if (sequence.isMissingNode()) sequence = firstClaimValue(publication, "sequences");
        String series = sequence.isObject() ? text(sequence, "name") : "";
        Double sequenceNumber = null;
        if (sequence.isObject()) {
            JsonNode number = sequence.path("number");
            if (number.has("value") && number.get("value").isNumber()) sequenceNumber = number.get("value").asDouble();
            else if (number.has("text")) sequenceNumber = parseDouble(number.get("text").asString());
        }

        List<String> genres = new ArrayList<>();
        for (JsonNode value : claimValues(bibliographic, "genres")) {
            if (value.isObject()) addNonBlank(genres, text(value, "code")); else addNonBlank(genres, scalarText(value));
        }

        String language = LanguageResolver.resolveValue(firstClaimText(bibliographic, "language"));
        String rawIsbn = firstClaimText(publication, "isbn");
        String isbn = Isbn.tryParse(rawIsbn).map(Isbn::value).orElse("");
        List<ImportIssue> issues = mapIssues(root.path("issues"), sourceBookId);
        if (!rawIsbn.isBlank() && isbn.isBlank()) {
            issues = append(issues, new ImportIssue(ImportSeverity.WARNING, "normalize", "INVALID_ISBN",
                    sourceBookId, "Invalid ISBN ignored", false, Map.of("value", rawIsbn)));
        }

        Integer year = yearValue(firstClaimValue(publication, "year"));
        String publisher = firstClaimText(publication, "publisher");
        String city = firstClaimText(publication, "city");
        String annotation = firstClaimText(bibliographic, "annotation");
        List<String> keywords = stringsFromClaims(bibliographic, "keywords");
        Double rating = ratingValue(firstClaimValue(catalog, "rating"));
        boolean deleted = deletedValue(firstClaimValue(catalog, "deleted"));

        List<ExternalIdentity> identities = topLevelIdentities(root.path("identities"));
        List<CatalogArtifact> artifacts = artifacts(root.path("artifacts"));
        CatalogArtifact primary = artifacts.isEmpty() ? null : artifacts.getFirst();
        String fileName = primary == null ? "" : primary.name();
        String format = primary == null ? "" : primary.fileFormat();
        String archive = primary == null ? "" : primary.archive();
        String archiveEntry = primary == null ? "" : primary.archiveEntry();
        Long size = primary == null ? null : primary.size();

        Map<String, String> sourceMetadata = new LinkedHashMap<>();
        sourceMetadata.put("dataset", dataset.datasetId());
        sourceMetadata.put("library", recordLibrary);
        sourceMetadata.put("locator.kind", locatorKind);
        sourceMetadata.put("locator.source", locatorSource);
        if (locator.has("index")) sourceMetadata.put("locator.index", locator.path("index").asString());
        if (locator.has("book_id")) sourceMetadata.put("locator.book_id", locator.path("book_id").asString());
        sourceMetadata.put("record.number", Long.toString(recordNumber));
        // Versioned raw structured metadata prevents irreversible loss of extended metabib fields.
        sourceMetadata.put("metabib.record.schema", RECORD_SCHEMA);
        sourceMetadata.put("metabib.record.json", root.toString());
        if (root.has("relations")) sourceMetadata.put("metabib.relations.json", root.get("relations").toString());
        if (root.has("observations")) sourceMetadata.put("metabib.observations.json", root.get("observations").toString());
        if (root.has("claims")) sourceMetadata.put("metabib.claims.json", root.get("claims").toString());
        if (root.has("identities")) sourceMetadata.put("metabib.identities.json", root.get("identities").toString());
        if (root.has("artifacts")) sourceMetadata.put("metabib.artifacts.json", root.get("artifacts").toString());
        flattenRelations(root.path("relations"), sourceMetadata);

        return new CatalogRecord(sourceBookId, title, authors, series, sequenceNumber, genres, language,
                format, fileName, archive, archiveEntry, size, deleted, isbn, publisher, year, city,
                translators, annotation, keywords, rating, null, sourceMetadata, artifacts, identities, issues);
    }

    private static Map<String, String> datasetMetadata(JsonNode header) {
        Map<String, String> metadata = new LinkedHashMap<>();
        JsonNode generator = header.path("generator");
        JsonNode normalization = header.path("normalization");
        JsonNode database = header.path("database");

        putIfNonBlank(metadata, "generator.name", text(generator, "name"));
        putIfNonBlank(metadata, "generator.version", text(generator, "version"));
        putIfNonBlank(metadata, "normalization.model", text(normalization, "model"));
        putIfNonBlank(metadata, "database.id", text(database, "id"));
        putIfNonBlank(metadata, "database.format", text(database, "format"));
        putIfNonBlank(metadata, "database.dump_date", firstNonBlank(
                text(database, "dump_date"), text(header, "dump_date")));
        putIfNonBlank(metadata, "database.dump_checksum", firstNonBlank(
                text(database, "dump_checksum"), text(header, "dump_checksum")));

        if (database.has("dumps")) metadata.put("database.dumps.json", database.get("dumps").toString());
        if (header.has("ordering")) metadata.put("ordering.json", header.get("ordering").toString());
        if (header.has("processing")) metadata.put("processing.json", header.get("processing").toString());
        if (header.has("archives")) metadata.put("archives.json", header.get("archives").toString());
        if (header.has("features")) metadata.put("features.json", header.get("features").toString());
        putIfNonBlank(metadata, "dataset.created", text(header, "created"));
        metadata.put("metabib.header.json", header.toString());
        return Map.copyOf(metadata);
    }

    private static List<CatalogPerson> peopleClaims(
            JsonNode group,
            String name,
            MetabibDatasetValidator.Context validation,
            String datasetId) {
        List<CatalogPerson> people = new ArrayList<>();
        int ordinal = 0;
        for (JsonNode value : claimValues(group, name)) {
            ordinal++;
            if (value == null || value.isNull() || value.isMissingNode()) continue;

            String first = "";
            String middle = "";
            String last = "";
            String nickname = "";
            String displayName = "";
            String disambiguation = "";
            List<ExternalIdentity> identities = List.of();

            if (value.isObject()) {
                first = firstNonBlank(text(value, "first_name"), text(value, "first"));
                middle = firstNonBlank(text(value, "middle_name"), text(value, "middle"));
                last = firstNonBlank(text(value, "last_name"), text(value, "last"));
                nickname = text(value, "nickname");
                displayName = firstNonBlank(text(value, "display_name"), text(value, "name"));
                disambiguation = text(value, "disambiguation");
                identities = personIdentities(value.path("identities"));
            } else {
                displayName = scalarText(value);
                last = displayName;
            }

            if (first.isBlank() && middle.isBlank() && last.isBlank() && !displayName.isBlank()) {
                last = displayName;
            }
            if (first.isBlank() && middle.isBlank() && last.isBlank()
                    && nickname.isBlank() && displayName.isBlank()) {
                continue;
            }

            if (validation != null && validation.isAmbiguousAuthor(first, middle, last)
                    && disambiguation.isBlank()) {
                ExternalIdentity identity = identities.stream().filter(ExternalIdentity::usable).findFirst().orElse(null);
                disambiguation = identity != null
                        ? "identity:" + identity.scheme() + ":" + identity.value()
                        : "ambiguous:" + firstNonBlank(datasetId, "dataset") + ":" + ordinal;
            }
            people.add(new CatalogPerson(first, middle, last, nickname, displayName, disambiguation, identities));
        }
        return List.copyOf(people);
    }

    private static List<ExternalIdentity> personIdentities(JsonNode array) {
        if (!array.isArray()) return List.of();
        List<ExternalIdentity> identities = new ArrayList<>();
        for (JsonNode identity : array) {
            String scheme = text(identity, "scheme");
            String value = text(identity, "value");
            if (!scheme.isBlank() && !value.isBlank()) {
                identities.add(new ExternalIdentity(scheme, value));
            }
        }
        return List.copyOf(identities);
    }

    private static List<ExternalIdentity> topLevelIdentities(JsonNode identities) {
        if (!identities.isObject()) return List.of();
        List<ExternalIdentity> out = new ArrayList<>();
        for (String group : List.of("catalog", "document", "publication")) {
            JsonNode array = identities.path(group);
            if (!array.isArray()) continue;
            for (JsonNode id : array) {
                String scheme = text(id, "scheme");
                String value = text(id, "value");
                if (!scheme.isBlank() && !value.isBlank()) out.add(new ExternalIdentity(group + ":" + scheme, value));
            }
        }
        return List.copyOf(out);
    }

    private static List<CatalogArtifact> artifacts(JsonNode array) {
        if (!array.isArray()) return List.of();
        List<CatalogArtifact> out = new ArrayList<>();
        for (JsonNode artifact : array) {
            String name = text(artifact, "name");
            String mediaType = text(artifact, "media_type");
            Long size = null;
            if (artifact.path("size").isArray()) {
                for (JsonNode x : artifact.path("size")) if (x.path("value").canConvertToLong()) { size = x.path("value").asLong(); break; }
            }
            String sha256 = "";
            if (artifact.path("checksums").isArray()) {
                for (JsonNode x : artifact.path("checksums")) {
                    if ("sha256".equalsIgnoreCase(text(x, "algorithm")) || "sha-256".equalsIgnoreCase(text(x, "algorithm"))) {
                        sha256 = text(x, "value"); break;
                    }
                }
            }
            String archive = "", entry = "";
            JsonNode occurrences = artifact.path("occurrences");
            if (occurrences.isArray() && !occurrences.isEmpty()) {
                JsonNode occurrence = occurrences.get(0);
                archive = text(occurrence, "archive");
                entry = text(occurrence, "entry");
            }
            String format = extensionOf(name);
            String fp = text(artifact, "fp");
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("metabib.artifact.json", artifact.toString());
            if (artifact.has("size")) metadata.put("metabib.size.json", artifact.get("size").toString());
            if (artifact.has("checksums")) metadata.put("metabib.checksums.json", artifact.get("checksums").toString());
            if (artifact.has("occurrences")) metadata.put("metabib.occurrences.json", artifact.get("occurrences").toString());
            if (occurrences.isArray()) {
                metadata.put("occurrence.count", Integer.toString(occurrences.size()));
                for (int i = 0; i < occurrences.size(); i++) {
                    JsonNode occurrence = occurrences.get(i);
                    String prefix = "occurrence." + i + ".";
                    putIfNonBlank(metadata, prefix + "archive", text(occurrence, "archive"));
                    putIfNonBlank(metadata, prefix + "entry", text(occurrence, "entry"));
                    if (occurrence.path("index").canConvertToInt()) metadata.put(prefix + "index", occurrence.path("index").asString());
                    if (occurrence.path("compressed_size").canConvertToLong()) metadata.put(prefix + "compressed_size", occurrence.path("compressed_size").asString());
                    if (occurrence.path("uncompressed_size").canConvertToLong()) metadata.put(prefix + "uncompressed_size", occurrence.path("uncompressed_size").asString());
                    putIfNonBlank(metadata, prefix + "modified", text(occurrence, "modified"));
                }
            }
            out.add(new CatalogArtifact(name, mediaType, format, archive, entry, size, sha256, fp, metadata));
        }
        return List.copyOf(out);
    }

    private static void flattenRelations(JsonNode relations, Map<String, String> metadata) {
        if (!relations.isArray()) {
            metadata.put("metabib.relation.count", "0");
            return;
        }
        metadata.put("metabib.relation.count", Integer.toString(relations.size()));
        for (int i = 0; i < relations.size(); i++) {
            JsonNode relation = relations.get(i);
            String prefix = "metabib.relation." + i + ".";
            putIfNonBlank(metadata, prefix + "type", text(relation, "type"));
            putIfNonBlank(metadata, prefix + "observation", text(relation, "observation"));
            JsonNode target = relation.path("target");
            putIfNonBlank(metadata, prefix + "target.scheme", text(target, "scheme"));
            putIfNonBlank(metadata, prefix + "target.value", text(target, "value"));
            putIfNonBlank(metadata, prefix + "event_id", text(relation, "event_id"));
            putIfNonBlank(metadata, prefix + "time", text(relation, "time"));
            if (relation.has("participants")) metadata.put(prefix + "participants.json", relation.get("participants").toString());
            metadata.put(prefix + "raw.json", relation.toString());
        }
    }

    private static void putIfNonBlank(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static List<ImportIssue> mapIssues(JsonNode array, String sourceBookId) {
        if (!array.isArray()) return List.of();
        List<ImportIssue> out = new ArrayList<>();
        for (JsonNode issue : array) {
            out.add(new ImportIssue(ImportSeverity.WARNING, text(issue, "stage"), text(issue, "code"), sourceBookId,
                    text(issue, "message"), issue.path("retryable").asBoolean(false),
                    text(issue, "path").isBlank() ? Map.of() : Map.of("path", text(issue, "path"))));
        }
        return List.copyOf(out);
    }

    private static List<ImportIssue> append(List<ImportIssue> source, ImportIssue issue) {
        ArrayList<ImportIssue> out = new ArrayList<>(source); out.add(issue); return List.copyOf(out);
    }

    private static JsonNode firstClaimValue(JsonNode group, String name) {
        JsonNode array = group.path(name);
        if (!array.isArray() || array.isEmpty()) return tools.jackson.databind.node.MissingNode.getInstance();
        return array.get(0).path("value");
    }
    private static List<JsonNode> claimValues(JsonNode group, String name) {
        JsonNode array = group.path(name);
        if (!array.isArray()) return List.of();
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode claim : array) if (claim.has("value")) out.add(claim.get("value"));
        return out;
    }
    private static String firstClaimText(JsonNode group, String name) { return scalarText(firstClaimValue(group, name)); }
    private static List<String> stringsFromClaims(JsonNode group, String name) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (JsonNode value : claimValues(group, name)) {
            if (value.isArray()) for (JsonNode x : value) addNonBlank(out, scalarText(x));
            else addNonBlank(out, scalarText(value));
        }
        return List.copyOf(out);
    }
    private static String scalarText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isString() || node.isNumber() || node.isBoolean()) return node.asString().trim();
        if (node.isObject()) {
            for (String candidate : List.of("text", "value", "name", "raw")) {
                JsonNode x = node.get(candidate); if (x != null && x.isValueNode()) return x.asString().trim();
            }
        }
        return "";
    }
    private static Integer yearValue(JsonNode value) {
        if (value == null || value.isMissingNode()) return null;
        if (value.isObject() && value.path("value").canConvertToInt()) return value.path("value").asInt();
        String text = scalarText(value);
        var m = java.util.regex.Pattern.compile("(?<!\\d)(18|19|20|21)\\d{2}(?!\\d)").matcher(text);
        return m.find() ? Integer.valueOf(m.group()) : null;
    }
    private static Double ratingValue(JsonNode value) {
        if (value == null || value.isMissingNode()) return null;
        if (value.isObject() && value.path("average").isNumber()) return value.path("average").asDouble();
        return parseDouble(scalarText(value));
    }
    private static boolean deletedValue(JsonNode value) {
        String state = value != null && value.isObject() ? text(value, "state") : scalarText(value);
        state = state.toLowerCase(Locale.ROOT);
        return state.equals("deleted") || state.equals("removed") || state.equals("true") || state.equals("1") || state.equals("yes");
    }
    static String text(JsonNode node, String field) { JsonNode x = node == null ? null : node.get(field); return x == null || x.isNull() ? "" : x.asString("").trim(); }
    private static Double parseDouble(String value) { try { return value == null || value.isBlank() ? null : Double.valueOf(value.trim()); } catch (Exception e) { return null; } }
    private static String extensionOf(String name) { int dot = name == null ? -1 : name.lastIndexOf('.'); return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : ""; }
    private static String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v.trim(); return ""; }
    private static <T extends Collection<String>> void addNonBlank(T out, String value) { if (value != null && !value.isBlank()) out.add(value.trim()); }
    private static String stripBom(String line) { return line != null && !line.isEmpty() && line.charAt(0) == '\uFEFF' ? line.substring(1) : line; }
    private static String readNonBlank(BufferedReader reader) throws IOException {
        while (true) {
            String line = readBoundedLine(reader);
            if (line == null || !line.isBlank()) return line;
        }
    }

    private static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(4096, MAX_JSONL_RECORD_CHARS));
        while (true) {
            int next = reader.read();
            if (next < 0) return line.isEmpty() ? null : line.toString();
            if (next == '\n') return line.toString();
            if (next == '\r') {
                reader.mark(1);
                int after = reader.read();
                if (after >= 0 && after != '\n') reader.reset();
                return line.toString();
            }
            if (line.length() >= MAX_JSONL_RECORD_CHARS) {
                throw new IOException("Metabib JSONL record exceeds safety limit of "
                        + MAX_JSONL_RECORD_CHARS + " characters");
            }
            line.append((char) next);
        }
    }

    private static OpenedInput openInput(Path source) throws IOException {
        Path safeSource = SafeFilePublication.requireSafeRegularFile(source, "Metabib source");
        String name = safeSource.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jsonl")) {
            return new OpenedInput(Files.newInputStream(safeSource, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), null);
        }
        if (name.endsWith(".jsonl.gz")) {
            InputStream raw = Files.newInputStream(safeSource, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try { return new OpenedInput(new GZIPInputStream(raw, 128 * 1024), null); }
            catch (IOException | RuntimeException failure) { try { raw.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } throw failure; }
        }
        if (name.endsWith(".jsonl.zst")) {
            InputStream raw = Files.newInputStream(safeSource, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try { return new OpenedInput(new ZstdInputStream(raw), null); }
            catch (IOException | RuntimeException failure) { try { raw.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } throw failure; }
        }
        if (name.endsWith(".zip")) {
            SafeZipArchive opened = SafeZipArchive.openDetected(safeSource, "Metabib ZIP source");
            try {
                ZipFile zip = opened.zip();
                ZipArchiveEntry entry = selectZipDatasetEntry(zip);
                if (entry == null) throw new IOException("ZIP contains no .jsonl dataset: " + source);
                InputStream bounded = new LimitedInputStream(
                        zip.getInputStream(entry), ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES);
                return new OpenedInput(bounded, opened);
            } catch (IOException | RuntimeException failure) {
                try { opened.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        throw new IOException("Unsupported metabib container: " + source);
    }

    private static ZipArchiveEntry selectZipDatasetEntry(ZipFile zip) throws IOException {
        ZipArchiveEntry selected = null;
        int count = 0;
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            if (++count > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                throw new IOException("Metabib ZIP contains too many entries: " + count);
            }
            if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".jsonl")) continue;
            long size = entry.getSize();
            long compressed = entry.getCompressedSize();
            if (size > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                throw new IOException("Metabib JSONL entry is too large: " + entry.getName());
            }
            if (ArchiveSafetyLimits.exceedsCompressionRatio(size, compressed)) {
                throw new IOException("Metabib JSONL entry has suspicious compression ratio: " + entry.getName());
            }
            if (selected == null || entry.getName().compareTo(selected.getName()) < 0) selected = entry;
        }
        return selected;
    }

    private record OpenedInput(InputStream stream, SafeZipArchive zip) implements AutoCloseable {
        @Override public void close() throws IOException {
            IOException error = null;
            try { stream.close(); } catch (IOException e) { error = e; }
            if (zip != null) try { zip.close(); } catch (IOException e) { if (error == null) error = e; }
            if (error != null) throw error;
        }
    }
}
