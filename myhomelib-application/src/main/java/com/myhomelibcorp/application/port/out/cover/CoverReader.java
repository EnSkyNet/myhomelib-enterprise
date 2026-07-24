package com.myhomelibcorp.application.port.out.cover;

import com.myhomelibcorp.application.dto.BookDto;

/**
 * Читає обкладинку з файлової системи або архіву.
 * Повертає масив байтів зображення.
 */
public interface CoverReader {
    byte[] readCover(BookDto book);
}