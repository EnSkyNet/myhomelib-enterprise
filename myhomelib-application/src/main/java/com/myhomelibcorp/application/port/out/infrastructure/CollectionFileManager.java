package com.myhomelibcorp.application.port.out.infrastructure;

import com.myhomelibcorp.domain.model.collection.Collection;

/**
 * Порт для управління фізичними файлами колекції (БД, індекси).
 * Реалізується в інфраструктурному шарі.
 */
public interface CollectionFileManager {

    /**
     * Закриває поточне з'єднання з колекцією (якщо вона активна).
     */
    void closeIfCurrent(Collection collection);

    /**
     * Видаляє всі фізичні файли колекції (БД, індекси).
     */
    void deletePhysicalFiles(Collection collection);
}