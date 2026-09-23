package com.myhomelibcorp.application.sync.conflict;

import java.util.List;

/** UI-safe projection of one unresolved conflict; contains localization keys, never display-language prose. */
public record SyncConflictReviewItem(
        String logicalKey,
        Text entityType,
        Text reason,
        Text localSnapshot,
        Text remoteSnapshot
) {
    public SyncConflictReviewItem {
        if (logicalKey == null || logicalKey.isBlank()) throw new IllegalArgumentException("logicalKey is required");
        if (entityType == null) throw new IllegalArgumentException("entityType is required");
        if (reason == null) reason = Text.of("ui.sync.conflict.reason.manual");
        if (localSnapshot == null) localSnapshot = Text.of("ui.sync.conflict.snapshot.missing");
        if (remoteSnapshot == null) remoteSnapshot = Text.of("ui.sync.conflict.snapshot.missing");
    }

    /** Stable localization key plus plain data arguments. */
    public record Text(String key, List<String> arguments) {
        public Text {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        public static Text of(String key, Object... arguments) {
            if (arguments == null || arguments.length == 0) return new Text(key, List.of());
            return new Text(key, java.util.Arrays.stream(arguments)
                    .map(value -> value == null ? "" : String.valueOf(value))
                    .toList());
        }
    }
}
