package com.myhomelibcorp.application.port.out.cover;

import com.myhomelibcorp.application.dto.BookDto;

/**
 * Основний сервіс для вилучення обкладинки.
 * Використовує кеш та Reader.
 */
public interface CoverExtractor {
    byte[] extractCover(BookDto book);
}