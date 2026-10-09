package com.myhomelibcorp.infrastructure.backup;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Performs streaming validation of portable user-data manifest headers and current-schema shape.
 *
 * <p>The validator deliberately does not restore or migrate data. Keeping validation separate makes
 * the fail-before-mutation boundary explicit while preserving streaming behavior for large exports.</p>
 */
final class PortableUserDataManifestValidator {
    private static final String CANONICAL_FORMAT = "myhomelib-user-data";

    private final ObjectMapper mapper;

    PortableUserDataManifestValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    ManifestHeader inspect(Path sourceFile, int maxSupportedVersion) throws IOException {
        Integer schemaVersion = null;
        Integer legacyVersion = null;
        String format = null;
        boolean schemaVersionSeen = false;
        boolean legacyVersionSeen = false;
        boolean formatSeen = false;

        try (JsonParser parser = mapper.createParser(sourceFile)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("schemaVersion".equals(field)) {
                    if (schemaVersionSeen) throw new IOException("Duplicate portable user-data field: schemaVersion");
                    schemaVersionSeen = true;
                    if (valueToken != JsonToken.VALUE_NUMBER_INT) {
                        throw new IOException("Portable user-data schemaVersion must be an integer");
                    }
                    schemaVersion = parser.getIntValue();
                } else if ("version".equals(field)) {
                    if (legacyVersionSeen) throw new IOException("Duplicate portable user-data field: version");
                    legacyVersionSeen = true;
                    if (valueToken != JsonToken.VALUE_NUMBER_INT) {
                        throw new IOException("Portable user-data version must be an integer");
                    }
                    legacyVersion = parser.getIntValue();
                } else if ("format".equals(field)) {
                    if (formatSeen) throw new IOException("Duplicate portable user-data field: format");
                    formatSeen = true;
                    if (valueToken != JsonToken.VALUE_STRING) {
                        throw new IOException("Portable user-data format must be a string");
                    }
                    format = parser.getValueAsString();
                }
                parser.skipChildren();
            }
        }

        if (schemaVersionSeen && legacyVersionSeen && !Objects.equals(schemaVersion, legacyVersion)) {
            throw new IOException("Conflicting portable user-data schema versions: schemaVersion="
                    + schemaVersion + ", version=" + legacyVersion);
        }
        if (!schemaVersionSeen && formatSeen) {
            throw new IOException("Portable user-data schemaVersion is required when format is present");
        }

        int version = schemaVersionSeen ? schemaVersion : legacyVersionSeen ? legacyVersion : 1;
        if (version < 1 || version > maxSupportedVersion) {
            throw new IOException("Unsupported portable user-data schema version: " + version);
        }
        if (version >= 2 && !CANONICAL_FORMAT.equals(format)) {
            throw new IOException(format == null
                    ? "Portable user-data format is missing for schema v" + version
                    : "Unsupported portable user-data format: " + format);
        }
        return new ManifestHeader(version, format);
    }

    /**
     * Performs a cheap streaming preflight of the current (v2+) manifest before any user data is changed.
     * Large arrays are skipped rather than materialized, but all required top-level sections and
     * reader-settings containers must have the expected shape.
     */
    void validateCurrentStructure(Path sourceFile, int sourceVersion) throws IOException {
        Map<String, JsonToken> required = new LinkedHashMap<>();
        for (String name : List.of("bookState", "readingProgress", "readingHistory", "readingStats",
                "bookmarks", "groups", "groupMemberships", "savedSearches")) {
            required.put(name, JsonToken.START_ARRAY);
        }
        if (sourceVersion >= 3) {
            required.put("customFieldDefinitions", JsonToken.START_ARRAY);
            required.put("customFieldValues", JsonToken.START_ARRAY);
        }
        if (sourceVersion >= 4) {
            required.put("annotations", JsonToken.START_ARRAY);
            required.put("annotationTags", JsonToken.START_ARRAY);
        }
        if (sourceVersion >= 5) {
            required.put("readingLists", JsonToken.START_ARRAY);
            required.put("readingListItems", JsonToken.START_ARRAY);
        }
        required.put("filterSettings", JsonToken.START_OBJECT);
        required.put("readerSettings", JsonToken.START_OBJECT);
        Set<String> seen = new HashSet<>();

        try (JsonParser parser = mapper.createParser(sourceFile)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Portable user-data manifest must be a JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken token = parser.nextToken();
                JsonToken expected = required.get(field);
                if (expected == null) {
                    parser.skipChildren();
                    continue;
                }
                if (!seen.add(field)) throw new IOException("Duplicate portable user-data section: " + field);
                if (token != expected) {
                    throw new IOException("Portable user-data section has invalid type: " + field);
                }
                if ("readerSettings".equals(field)) {
                    validateReaderSettingsStructure(parser, sourceVersion);
                } else {
                    parser.skipChildren();
                }
            }
        }
        Set<String> missing = new LinkedHashSet<>(required.keySet());
        missing.removeAll(seen);
        if (!missing.isEmpty()) throw new IOException("Portable user-data sections are missing: " + missing);
    }

    private void validateReaderSettingsStructure(JsonParser parser, int sourceVersion) throws IOException {
        Set<String> seen = new HashSet<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            JsonToken token = parser.nextToken();
            if (!seen.add(field)) throw new IOException("Duplicate readerSettings field: " + field);
            if ("global".equals(field)) {
                if (token != JsonToken.VALUE_NULL && token != JsonToken.START_OBJECT) {
                    throw new IOException("readerSettings.global must be an object or null");
                }
                parser.skipChildren();
            } else if ("perBook".equals(field)) {
                if (token != JsonToken.START_ARRAY) throw new IOException("readerSettings.perBook must be an array");
                parser.skipChildren();
            } else if ("scoped".equals(field)) {
                if (token != JsonToken.START_ARRAY) throw new IOException("readerSettings.scoped must be an array");
                parser.skipChildren();
            } else {
                parser.skipChildren();
            }
        }
        if (!seen.contains("global")) throw new IOException("readerSettings.global is missing");
        if (!seen.contains("perBook")) throw new IOException("readerSettings.perBook is missing");
        if (sourceVersion >= 6 && !seen.contains("scoped")) throw new IOException("readerSettings.scoped is missing");
    }

    record ManifestHeader(int sourceVersion, String format) { }
}
