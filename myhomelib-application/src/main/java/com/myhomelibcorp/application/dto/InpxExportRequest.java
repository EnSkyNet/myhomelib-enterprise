package com.myhomelibcorp.application.dto;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.List;

@Value
@Builder
public class InpxExportRequest {
    List<BookId> bookIds;          // Якщо null – експортуємо всі книги
    Path outputFile;                // Шлях до вихідного .inpx файлу
    String collectionName;          // Назва колекції
    String collectionVersion;       // Версія колекції
}