package com.myhomelibcorp.application.port.out.infrastructure;

/**
 * Порт для ініціалізації бази даних поточної колекції.
 * Реалізація знаходиться в інфраструктурному шарі.
 */
public interface DatabaseInitializerPort {

    /**
     * Виконує міграції Flyway та інші необхідні дії для поточної колекції.
     * Якщо колекція не вибрана – нічого не робить.
     */
    void initializeCurrentCollection();
}