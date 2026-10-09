package com.myhomelibcorp.application.kobo;

import java.util.List;

public record KoboSyncBatch(List<KoboLibraryChange> changes, KoboSyncCursor nextCursor, boolean hasMore) {
    public KoboSyncBatch {
        changes = changes == null ? List.of() : List.copyOf(changes);
        nextCursor = nextCursor == null ? KoboSyncCursor.initial() : nextCursor;
    }
}
