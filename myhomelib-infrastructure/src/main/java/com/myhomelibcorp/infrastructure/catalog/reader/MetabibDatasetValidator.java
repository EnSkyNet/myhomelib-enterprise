package com.myhomelibcorp.infrastructure.catalog.reader;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.*;

/**
 * Structural validation for metabib.dataset/1 headers and records.
 * Kept separate from streaming/record mapping so validation policy has one focused home.
 */
final class MetabibDatasetValidator {
    private MetabibDatasetValidator() { }

    static Context validateHeader(JsonNode header, Path source) {
        String schema = MetabibCatalogReader.text(header, "schema");
        String recordSchema = MetabibCatalogReader.text(header, "record_schema");
        if (!MetabibCatalogReader.DATASET_SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("Unsupported metabib dataset schema in " + source + ": " + schema);
        }
        if (!MetabibCatalogReader.RECORD_SCHEMA.equals(recordSchema)) {
            throw new IllegalArgumentException("Unsupported metabib record schema in " + source + ": " + recordSchema);
        }
        for (String field : List.of("id", "library", "created")) {
            if (MetabibCatalogReader.text(header, field).isBlank()) {
                throw new IllegalArgumentException("Missing metabib header field: " + field);
            }
        }
        if (!header.path("records").canConvertToLong() || header.path("records").asLong() < 0) {
            throw new IllegalArgumentException("Invalid metabib header records count");
        }
        JsonNode generator = header.path("generator");
        if (MetabibCatalogReader.text(generator, "name").isBlank()
                || MetabibCatalogReader.text(generator, "version").isBlank()) {
            throw new IllegalArgumentException("Invalid metabib generator metadata");
        }
        if (MetabibCatalogReader.text(header.path("normalization"), "model").isBlank()) {
            throw new IllegalArgumentException("Invalid metabib normalization metadata");
        }
        JsonNode ordering = header.path("ordering");
        if (!Set.of("archive_entry", "database_book_id").contains(MetabibCatalogReader.text(ordering, "mode"))
                || !"ascending".equals(MetabibCatalogReader.text(ordering, "direction"))) {
            throw new IllegalArgumentException("Invalid metabib ordering metadata");
        }
        JsonNode processing = header.path("processing");
        if (!processing.path("parse_fb2").isBoolean()
                || !processing.path("archive_content_checksum").isObject()
                || !processing.path("archive_content_checksum").path("enabled").isBoolean()) {
            throw new IllegalArgumentException("Invalid metabib processing metadata");
        }

        Map<String, ArchiveDescriptor> archives = new LinkedHashMap<>();
        Set<Integer> ordinals = new HashSet<>();
        JsonNode archiveArray = header.path("archives");
        if (archiveArray.isArray()) {
            for (JsonNode a : archiveArray) {
                String id = MetabibCatalogReader.text(a, "id");
                String name = MetabibCatalogReader.text(a, "name");
                int ordinal = a.path("ordinal").asInt(-1);
                long entries = a.path("entries").asLong(-1);
                long fb2Entries = a.path("fb2_entries").asLong(-1);
                if (id.isBlank() || name.isBlank() || ordinal < 0 || entries < 0 || fb2Entries < 0 || fb2Entries > entries) {
                    throw new IllegalArgumentException("Invalid metabib archive descriptor: " + id + "/" + name);
                }
                if (!ordinals.add(ordinal)) {
                    throw new IllegalArgumentException("Duplicate metabib archive ordinal: " + ordinal);
                }
                List<IndexRange> ignored = parseRanges(a.path("ignored"), entries, "ignored", name);
                List<IndexRange> dummy = parseRanges(a.path("dummy"), entries, "dummy", name);
                ensureNoOverlap(ignored, dummy, name);
                ArchiveDescriptor descriptor = new ArchiveDescriptor(id, name, ordinal, entries, ignored, dummy);
                putArchiveKey(archives, id, descriptor);
                if (!name.equals(id)) putArchiveKey(archives, name, descriptor);
            }
        }

        Set<String> ambiguousKeys = new HashSet<>();
        JsonNode ambiguous = header.path("database").path("inpx").path("ambiguous_db_authors");
        if (ambiguous.isArray()) {
            for (JsonNode group : ambiguous) {
                String key = normalizePersonKey(MetabibCatalogReader.text(group, "key"));
                if (!key.isBlank()) ambiguousKeys.add(key);
            }
        }
        return new Context(header.path("records").asLong(), Map.copyOf(archives), Set.copyOf(ambiguousKeys));
    }

    private static void putArchiveKey(Map<String, ArchiveDescriptor> archives, String key, ArchiveDescriptor descriptor) {
        ArchiveDescriptor existing = archives.putIfAbsent(key, descriptor);
        if (existing != null && existing != descriptor) {
            throw new IllegalArgumentException("Duplicate metabib archive id/name: " + descriptor.id() + "/" + descriptor.name());
        }
    }

    static void validateRecordStructure(JsonNode root, Context validation, long recordNumber) {
        JsonNode descriptor = root.path("record");
        JsonNode locator = descriptor.path("locator");
        if (!descriptor.isObject() || MetabibCatalogReader.text(descriptor, "library").isBlank()) {
            throw new IllegalArgumentException("record " + recordNumber + ": invalid record descriptor");
        }
        String kind = MetabibCatalogReader.text(locator, "kind");
        if (!Set.of("archive_entry", "database_book").contains(kind)
                || MetabibCatalogReader.text(locator, "source").isBlank()) {
            throw new IllegalArgumentException("record " + recordNumber + ": invalid locator");
        }
        if (kind.equals("archive_entry") && !locator.path("index").canConvertToInt()) {
            throw new IllegalArgumentException("record " + recordNumber + ": archive locator without index");
        }
        if (kind.equals("database_book") && !locator.path("book_id").canConvertToLong()) {
            throw new IllegalArgumentException("record " + recordNumber + ": database locator without book_id");
        }

        JsonNode observations = root.path("observations");
        if (!observations.isArray()) {
            throw new IllegalArgumentException("record " + recordNumber + ": observations is not array");
        }
        Set<String> observationIds = new HashSet<>();
        for (JsonNode observation : observations) {
            String id = MetabibCatalogReader.text(observation, "id");
            if (id.isBlank() || !observationIds.add(id)) {
                throw new IllegalArgumentException("record " + recordNumber + ": duplicate/blank observation id " + id);
            }
        }
        for (JsonNode observation : observations) {
            String parent = MetabibCatalogReader.text(observation, "parent");
            if (!parent.isBlank() && !observationIds.contains(parent)) {
                throw new IllegalArgumentException("record " + recordNumber + ": observation parent not found: " + parent);
            }
        }
        for (String section : List.of("identities", "artifacts", "claims", "relations", "issues")) {
            validateObservationReferences(root.path(section), observationIds, recordNumber, section);
        }
        validateArtifactOccurrences(root.path("artifacts"), validation, recordNumber);
    }

    private static void validateObservationReferences(JsonNode node, Set<String> ids, long recordNumber, String path) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node) {
                validateObservationReferences(child, ids, recordNumber, path + "[" + i++ + "]");
            }
            return;
        }
        if (!node.isObject()) return;
        JsonNode ref = node.get("observation");
        if (ref != null && ref.isTextual() && !ref.asText().isBlank() && !ids.contains(ref.asText())) {
            throw new IllegalArgumentException("record " + recordNumber + ": missing observation " + ref.asText() + " at " + path);
        }
        node.fields().forEachRemaining(e -> validateObservationReferences(e.getValue(), ids, recordNumber, path + "." + e.getKey()));
    }

    private static void validateArtifactOccurrences(JsonNode artifacts, Context validation, long recordNumber) {
        if (!artifacts.isArray()) return;
        for (JsonNode artifact : artifacts) {
            JsonNode occurrences = artifact.path("occurrences");
            if (!occurrences.isArray()) continue;
            for (JsonNode occurrence : occurrences) {
                String archive = MetabibCatalogReader.text(occurrence, "archive");
                String entry = MetabibCatalogReader.text(occurrence, "entry");
                int index = occurrence.path("index").asInt(-1);
                ArchiveDescriptor descriptor = validation.archive(archive);
                if (descriptor == null) {
                    throw new IllegalArgumentException("record " + recordNumber + ": occurrence references unknown archive " + archive);
                }
                if (entry.isBlank() || index < 0 || index >= descriptor.entries()) {
                    throw new IllegalArgumentException("record " + recordNumber + ": invalid occurrence " + archive + "#" + index);
                }
            }
        }
    }

    private static List<IndexRange> parseRanges(JsonNode ranges, long entries, String kind, String archive) {
        if (!ranges.isArray()) return List.of();
        List<IndexRange> out = new ArrayList<>();
        for (JsonNode range : ranges) {
            long start = range.path("start").asLong(-1);
            long end = range.path("end").asLong(-1);
            if (start < 0 || end < start || end >= entries) {
                throw new IllegalArgumentException("Invalid " + kind + " range in archive " + archive + ": " + start + ".." + end);
            }
            out.add(new IndexRange(start, end));
        }
        out.sort(Comparator.comparingLong(IndexRange::start));
        for (int i = 1; i < out.size(); i++) {
            if (out.get(i).start() <= out.get(i - 1).end()) {
                throw new IllegalArgumentException("Overlapping " + kind + " ranges in archive " + archive);
            }
        }
        return List.copyOf(out);
    }

    private static void ensureNoOverlap(List<IndexRange> a, List<IndexRange> b, String archive) {
        for (IndexRange x : a) {
            for (IndexRange y : b) {
                if (x.start() <= y.end() && y.start() <= x.end()) {
                    throw new IllegalArgumentException("ignored/dummy ranges overlap in archive " + archive);
                }
            }
        }
    }

    private static String normalizePersonKey(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private record IndexRange(long start, long end) {
        boolean contains(long index) { return index >= start && index <= end; }
    }

    private record ArchiveDescriptor(String id, String name, int ordinal, long entries,
                                     List<IndexRange> ignored, List<IndexRange> dummy) {
        boolean dummy(long index) { return dummy.stream().anyMatch(range -> range.contains(index)); }
    }

    record Context(Long declaredRecords, Map<String, ArchiveDescriptor> archives, Set<String> ambiguousAuthorKeys) {
        ArchiveDescriptor archive(String key) { return key == null ? null : archives.get(key); }

        boolean isDummyRecord(JsonNode root) {
            JsonNode locator = root.path("record").path("locator");
            if (!"archive_entry".equals(MetabibCatalogReader.text(locator, "kind"))) return false;
            ArchiveDescriptor archive = archive(MetabibCatalogReader.text(locator, "source"));
            return archive != null && archive.dummy(locator.path("index").asLong(-1));
        }

        boolean isAmbiguousAuthor(String first, String middle, String last) {
            return ambiguousAuthorKeys.contains(normalizePersonKey(last + "," + first + "," + middle));
        }
    }
}
