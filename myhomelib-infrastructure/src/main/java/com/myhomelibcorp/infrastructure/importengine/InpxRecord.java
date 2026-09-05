package com.myhomelibcorp.infrastructure.importengine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** One logical record from an INP/INPX index together with its source archive. */
public record InpxRecord(Map<String, String> fields, String inpName, String archiveName) {
    public InpxRecord {
        if (fields instanceof ParsedFields) {
            // InpxReader already normalized structure names and trimmed values. Avoid rebuilding
            // the same 13+ entry map for every record in a 700k-row catalog.
            fields = Collections.unmodifiableMap(fields);
        } else {
            Map<String, String> normalized = new LinkedHashMap<>();
            if (fields != null) {
                fields.forEach((k, v) -> normalized.put(
                        k == null ? "" : k.trim().toUpperCase(Locale.ROOT),
                        v == null ? "" : v.trim()));
            }
            fields = Collections.unmodifiableMap(normalized);
        }
        inpName = inpName == null ? "" : inpName;
        archiveName = archiveName == null ? "" : archiveName;
    }

    static Map<String, String> newParsedFields(int expectedSize) {
        return new ParsedFields(Math.max(16, expectedSize * 2));
    }

    static InpxRecord parsed(Map<String, String> normalizedFields, String inpName, String archiveName) {
        if (!(normalizedFields instanceof ParsedFields)) {
            normalizedFields = new ParsedFields(normalizedFields);
        }
        return new InpxRecord(normalizedFields, inpName, archiveName);
    }

    public String field(String name) {
        if (name == null) return "";
        // The import hot path calls this with uppercase field constants. Avoid trim/uppercase
        // allocation unless a compatibility caller supplies a non-canonical name.
        String direct = fields.get(name);
        if (direct != null) return direct;
        return fields.getOrDefault(name.trim().toUpperCase(Locale.ROOT), "");
    }

    private static final class ParsedFields extends LinkedHashMap<String, String> {
        private ParsedFields(int capacity) {
            super(capacity);
        }

        private ParsedFields(Map<String, String> source) {
            super(source == null ? Map.of() : source);
        }
    }
}
