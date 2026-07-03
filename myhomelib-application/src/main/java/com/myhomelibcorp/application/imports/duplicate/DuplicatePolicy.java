package com.myhomelibcorp.application.imports.duplicate;

public enum DuplicatePolicy {
    /**
     * Пропустити дублікат (не зберігати)
     */
    SKIP,

    /**
     * Замінити існуючу книгу новою
     */
    REPLACE,

    /**
     * Зберегти як нову книгу (з новим ID)
     */
    SAVE_AS_NEW,

    /**
     * Оновити існуючу книгу (merge)
     */
    MERGE
}