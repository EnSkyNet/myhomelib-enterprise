package com.myhomelibcorp.infrastructure.persistence;

import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.SortBy;
import org.springframework.stereotype.Component;

@Component
public class QueryOptimizer {

    public BookQuery optimize(BookQuery query) {
        // Видаляємо зайві умови, якщо вони не мають сенсу
        // Наприклад, якщо є authorId, то withoutSeries може бути непотрібним
        // Якщо текст порожній – прибираємо умови пошуку
        BookQuery.Builder builder = BookQuery.builder();
        if (query.authorId() != null) builder.authorId(query.authorId());
        if (query.seriesId() != null) builder.seriesId(query.seriesId());
        // ... інші поля

        // Додаємо оптимізацію: якщо сортування за автором, додаємо JOIN з authors
        if (query.sortBy() == SortBy.AUTHOR) {
            // можна додати спеціальну обробку
        }

        return builder.build();
    }
}