package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.infrastructure.JdbcTemplateProvider;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JdbcTemplateProviderAdapter implements JdbcTemplateProvider {

    private final CollectionManager collectionManager;

    @Override
    public JdbcTemplate getCurrentJdbcTemplate() {
        if (!collectionManager.hasActiveCollection()) {
            throw new IllegalStateException("Колекцію не вибрано. Спочатку виберіть або створіть колекцію.");
        }
        return collectionManager.getCurrentJdbcTemplate();
    }
}