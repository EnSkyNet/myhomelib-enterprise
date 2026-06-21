package com.myhomelibcorp.application.port.out;

import java.util.List;

public interface SearchQueryService {

    /**
     * Пошук ідентифікаторів книг за текстовим запитом.
     * @param query текст пошуку
     * @param limit максимальна кількість результатів
     * @return список ID книг
     */
    List<String> searchBookIds(String query, int limit);
}