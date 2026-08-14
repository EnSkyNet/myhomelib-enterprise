package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.model.Chapter;

import java.util.List;

/**
 * Результат завантаження книги.
 */
public record ReaderBookContent(
        String html,
        List<Chapter> chapters
) {
}