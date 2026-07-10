package com.myhomelibcorp.application.port.out.infrastructure;

/**
 * Порт для оптимізації БД під час масового імпорту.
 * Реалізація залежить від конкретної СУБД (SQLite, PostgreSQL тощо).
 */
public interface BulkImportOptimizer {

    /**
     * Встановлює налаштування для максимальної швидкості вставки.
     */
    void enableBulkInsertMode();

    /**
     * Відновлює стандартні налаштування після імпорту.
     */
    void disableBulkInsertMode();
}