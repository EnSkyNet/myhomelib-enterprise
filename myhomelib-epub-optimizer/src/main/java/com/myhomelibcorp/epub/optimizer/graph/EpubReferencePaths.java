package com.myhomelibcorp.epub.optimizer.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** Canonical EPUB-internal reference path operations shared by graph rewrite stages. */
final class EpubReferencePaths {
    private EpubReferencePaths() {}

    static String parent(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    static String stripSuffix(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        int hash = raw.indexOf('#');
        int query = raw.indexOf('?');
        int cut = hash < 0 ? query : query < 0 ? hash : Math.min(hash, query);
        return cut < 0 ? raw : raw.substring(0, cut);
    }

    static String resolve(String base, String relative) {
        String value = relative == null ? "" : relative;
        String joined = (base == null || base.isBlank() ? "" : base + "/") + value.replace('\\', '/');
        Deque<String> parts = new ArrayDeque<>();
        for (String part : joined.split("/")) {
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.removeLast();
                else return "";
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts);
    }

    static String resolveReference(String base, String raw) {
        return resolve(base, stripSuffix(raw));
    }

    static String relative(String base, String target) {
        List<String> baseParts = parts(base);
        List<String> targetParts = parts(target);
        int common = 0;
        while (common < baseParts.size()
                && common < targetParts.size()
                && baseParts.get(common).equals(targetParts.get(common))) {
            common++;
        }
        List<String> result = new ArrayList<>();
        for (int i = common; i < baseParts.size(); i++) result.add("..");
        result.addAll(targetParts.subList(common, targetParts.size()));
        return String.join("/", result);
    }

    private static List<String> parts(String path) {
        if (path == null || path.isBlank()) return List.of();
        return Arrays.stream(path.split("/"))
                .filter(value -> !value.isBlank())
                .toList();
    }
}
