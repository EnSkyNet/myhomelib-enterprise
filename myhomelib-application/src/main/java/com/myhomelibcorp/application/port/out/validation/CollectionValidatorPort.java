package com.myhomelibcorp.application.port.out.validation;

import com.myhomelibcorp.application.dto.CreateCollectionRequest;

import java.util.List;

/**
 * Порт для валідації даних колекції.
 */
public interface CollectionValidatorPort {

    /**
     * Перевіряє, чи існує колекція з такою назвою.
     */
    boolean existsByName(String name);

    /**
     * Перевіряє, чи доступний шлях до БД.
     */
    boolean isDbPathAvailable(String dbPath);

    /**
     * Валідує запит на створення колекції.
     * @return список помилок або порожній список
     */
    List<String> validate(CreateCollectionRequest request);
}