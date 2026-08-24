package com.myhomelibcorp.infrastructure.importengine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** One logical record from an INP/INPX index together with its source archive. */
public record InpxRecord(Map<String, String> fields, String inpName, String archiveName) {
    public InpxRecord {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (fields != null) {
            fields.forEach((k, v) -> normalized.put(
                    k == null ? "" : k.trim().toUpperCase(Locale.ROOT),
                    v == null ? "" : v.trim()));
        }
        fields = Collections.unmodifiableMap(normalized);
        inpName = inpName == null ? "" : inpName;
        archiveName = archiveName == null ? "" : archiveName;
    }

    public String field(String name) {
        if (name == null) return "";
        return fields.getOrDefault(name.trim().toUpperCase(Locale.ROOT), "");
    }
}
