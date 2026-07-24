package com.myhomelibcorp.application.port.out.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Порт для отримання JdbcTemplate поточної колекції.
 */
public interface JdbcTemplateProvider {

    /**
     * Повертає JdbcTemplate для поточної активної колекції.
     * @throws IllegalStateException якщо колекцію не вибрано
     */
    JdbcTemplate getCurrentJdbcTemplate();
}