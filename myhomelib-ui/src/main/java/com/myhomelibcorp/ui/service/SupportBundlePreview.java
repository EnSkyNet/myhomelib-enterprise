package com.myhomelibcorp.ui.service;

import java.util.List;

/** Safe preview shown before writing the diagnostic ZIP. */
public record SupportBundlePreview(List<Item> items) {
    public SupportBundlePreview {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public String displayText() {
        StringBuilder out = new StringBuilder();
        for (Item item : items) {
            out.append(item.included() ? "✓ " : "– ").append(item.name());
            if (item.sourceBytes() >= 0) out.append(" (").append(humanSize(item.sourceBytes())).append(')');
            if (item.note() != null && !item.note().isBlank()) out.append(" — ").append(item.note());
            out.append('\n');
        }
        return out.toString();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024d);
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024d * 1024d));
    }

    public record Item(String name, long sourceBytes, boolean included, String note) { }
}
