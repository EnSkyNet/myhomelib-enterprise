package com.myhomelibcorp.application.catalog.collectioninfo;

import java.util.ArrayList;
import java.util.List;

/** Codec for the legacy MyHomeLib collection.info six-field layout. */
public final class CollectionInfoCodec {
    private CollectionInfoCodec() { }

    public static CollectionSourceProperties parse(String text) {
        if (text == null) throw new IllegalArgumentException("collection.info is null");
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] raw = normalized.split("\n", -1);
        if (raw.length < 5) {
            throw new IllegalArgumentException("Некоректний collection.info: очікується щонайменше 5 рядків");
        }
        int type = 0;
        if (raw.length > 2 && !raw[2].isBlank()) {
            try { type = Integer.parseInt(raw[2].trim()); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Некоректний type у collection.info: " + raw[2], e);
            }
        }
        String script = null;
        if (raw.length > 5) {
            List<String> scriptLines = new ArrayList<>();
            for (int i = 5; i < raw.length; i++) scriptLines.add(raw[i]);
            // A single terminal line break is a file terminator, not part of the script.
            if (!scriptLines.isEmpty() && scriptLines.get(scriptLines.size() - 1).isEmpty()) {
                scriptLines.remove(scriptLines.size() - 1);
            }
            if (!scriptLines.isEmpty()) script = String.join("\n", scriptLines);
        }
        return new CollectionSourceProperties(
                nullIfBlank(raw[0]),
                raw.length > 1 ? nullIfBlank(raw[1]) : null,
                type,
                raw.length > 3 ? nullIfBlankPreserve(raw[3]) : null,
                raw.length > 4 ? nullIfBlank(raw[4]) : null,
                nullIfBlankPreserve(script));
    }

    public static String serialize(CollectionSourceProperties info) {
        if (info == null) throw new IllegalArgumentException("collection.info properties are null");
        StringBuilder out = new StringBuilder();
        out.append(value(info.name())).append('\n');
        out.append(value(info.fileName())).append('\n');
        out.append(info.type()).append('\n');
        out.append(valuePreserve(info.notes())).append('\n');
        out.append(value(info.url())).append('\n');
        if (info.connectionScript() != null) {
            String script = info.connectionScript().replace("\r\n", "\n").replace('\r', '\n');
            out.append(script);
        }
        out.append('\n');
        return out.toString();
    }

    private static String value(String v) { return v == null ? "" : v.trim(); }
    private static String valuePreserve(String v) { return v == null ? "" : v; }
    private static String nullIfBlank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static String nullIfBlankPreserve(String v) { return v == null || v.isBlank() ? null : v; }
}
