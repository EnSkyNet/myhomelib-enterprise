package com.myhomelibcorp.ui.reader;

import java.time.LocalDateTime;

/**
 * Reader-facing immutable bookmark projection.
 *
 * <p>Keeps Reader UI/coordinators independent from the persistence domain model while retaining
 * exactly the fields required for display and navigation.</p>
 */
public record ReaderBookmarkItem(
        String id,
        String bookId,
        String paragraphId,
        int charOffset,
        double position,
        String chapterTitle,
        String context,
        LocalDateTime createdAt) {
}
