package com.myhomelibcorp.shared.exception;

public enum ErrorCode {
    // Domain errors
    BOOK_NOT_FOUND("BOOK-001", "Книгу не знайдено"),
    DUPLICATE_BOOK("BOOK-002", "Книга з такою назвою вже існує"),
    INVALID_BOOK_DATA("BOOK-003", "Некоректні дані книги"),
    AUTHOR_NOT_FOUND("AUTHOR-001", "Автора не знайдено"),
    GENRE_NOT_FOUND("GENRE-001", "Жанр не знайдено"),
    COLLECTION_NOT_FOUND("COLL-001", "Колекцію не знайдено"),

    // Application errors
    IMPORT_FAILED("IMP-001", "Помилка імпорту"),
    INVALID_FILE_FORMAT("IMP-002", "Непідтримуваний формат файлу"),
    SEARCH_FAILED("SEARCH-001", "Помилка пошуку"),

    // Technical errors
    DATABASE_ERROR("TECH-001", "Помилка бази даних"),
    IO_ERROR("TECH-002", "Помилка вводу-виводу"),
    CONFIG_ERROR("TECH-003", "Помилка конфігурації");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() { return code; }
    public String getDefaultMessage() { return defaultMessage; }
}