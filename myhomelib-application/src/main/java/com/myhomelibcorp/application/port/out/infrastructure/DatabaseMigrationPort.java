package com.myhomelibcorp.application.port.out.infrastructure;

/**
 * Порт для виконання міграцій бази даних.
 */
public interface DatabaseMigrationPort {

    /**
     * Виконує міграції для поточної колекції.
     * @return кількість виконаних міграцій
     */
    int migrateCurrentCollection();

    /**
     * Перевіряє, чи потрібні міграції.
     */
    boolean isMigrationNeeded();
}