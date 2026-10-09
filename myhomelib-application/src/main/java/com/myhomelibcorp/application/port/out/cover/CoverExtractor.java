package com.myhomelibcorp.application.port.out.cover;

import com.myhomelibcorp.application.dto.BookDto;

/**
 * Основний сервіс для вилучення обкладинки.
 * Використовує кеш та Reader.
 */
public interface CoverExtractor {
    byte[] extractCover(BookDto book);

    /**
     * Invalidates any cached cover material associated with the supplied book.
     * Implementations without a cache may keep the default no-op behavior.
     */
    default void invalidateCover(BookDto book) { }
}
