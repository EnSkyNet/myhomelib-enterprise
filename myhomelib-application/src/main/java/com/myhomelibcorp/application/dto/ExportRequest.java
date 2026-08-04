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
    boolean overwriteExisting;
    boolean extractOnly; // Для zip-архівів – витягти тільки один файл
    String customFileNameTemplate; // Шаблон імені файлу (опціонально)

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