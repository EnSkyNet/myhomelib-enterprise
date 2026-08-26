package com.myhomelibcorp.application.port.out.infrastructure;

import com.myhomelibcorp.domain.model.collection.Collection;

/**
 * Порт для управління життєвим циклом колекції.
 * Реалізується в інфраструктурному шарі.
 */
public interface CollectionLifecyclePort {

    /**
     * Переключає на іншу колекцію.
     */
    void switchToCollection(Collection collection);

    /**
     * Закриває поточну колекцію.
     */
    void closeCurrentCollection();

    /**
     * Отримує поточну колекцію.
     */
    Collection getCurrentCollection();

    /**
     * Оновлює metadata-опис уже відкритої колекції без перестворення DataSource.
     * Використовується після перейменування/зміни властивостей активної колекції.
     */
    void updateCurrentCollection(Collection collection);

    /**
     * Перевіряє, чи є активна колекція.
     */
    boolean hasActiveCollection();

    /**
     * Перевіряє, чи готова колекція до роботи.
     */
    boolean isCollectionReady();

    /**
     * Отримує розмір БД у байтах.
     */
    long getDatabaseSize();
}