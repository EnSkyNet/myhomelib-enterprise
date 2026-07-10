package com.myhomelibcorp.application.port.out.persistence;

/**
 * Порт для налаштування PRAGMA бази даних (оптимізація великих вставок).
 * Реалізація знаходиться в інфраструктурному шарі.
 */
public interface PragmaConfigurator {

    /**
     * Встановлює PRAGMA для максимальної швидкості вставки (перед великим імпортом).
     */
    void setPragmaForBulkInsert();

    /**
     * Відновлює стандартні PRAGMA (після імпорту).
     */
    void resetPragma();
}