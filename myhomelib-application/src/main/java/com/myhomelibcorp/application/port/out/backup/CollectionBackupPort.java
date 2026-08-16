package com.myhomelibcorp.application.port.out.backup;

import com.myhomelibcorp.domain.model.collection.Collection;

import java.nio.file.Path;

/**
 * Порт для операцій резервного копіювання та відновлення колекцій.
 * Реалізується в інфраструктурному шарі.
 */
public interface CollectionBackupPort {

    /**
     * Отримує поточну колекцію.
     */
    Collection getCurrentCollection();

    /**
     * Отримує шлях до файлу бази даних колекції.
     */
    String getDatabasePath(Collection collection);

    /**
     * Закриває поточну колекцію.
     */
    void closeCurrentCollection();

    /**
     * Повертає true, якщо є активна колекція.
     */
    boolean hasActiveCollection();
}