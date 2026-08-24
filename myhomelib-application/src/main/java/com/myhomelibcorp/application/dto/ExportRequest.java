package com.myhomelibcorp.application.dto;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.List;

@Value
@Builder
public class ExportRequest {
    List<BookId> bookIds;
    Path destinationFolder;
    ExportFormat format;
    /** Backward-compatible flag. New callers should use collisionPolicy. */
    boolean overwriteExisting;
    CollisionPolicy collisionPolicy;
    boolean extractOnly; // Для zip-архівів – витягти тільки один файл
    String customFileNameTemplate; // Шаблон імені файлу (опціонально)

    public CollisionPolicy effectiveCollisionPolicy() {
        if (collisionPolicy != null) return collisionPolicy;
        return overwriteExisting ? CollisionPolicy.OVERWRITE : CollisionPolicy.RENAME;
    }

    public enum CollisionPolicy {
        OVERWRITE, SKIP, RENAME
    }

    public enum ExportFormat {
        FB2,      // Звичайний FB2
        FB2_ZIP,  // FB2 в архіві zip
        TXT,      // Текстовий файл
        PDF,      // PDF
        EPUB,     // EPUB
        MOBI,     // MOBI
        LRF       // Sony Reader LRF
    }
}