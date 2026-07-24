package com.myhomelibcorp.application.port.out.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;

public interface JdbcTemplateProviderPort {
    JdbcTemplate getCurrentJdbcTemplate();
}