package com.myhomelibcorp.domain.model.book;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookLegacyPlaceholderTest {
    @Test
    void recognizesBothHistoricalUnknownAuthorSentinels() {
        assertThat(Book.isLegacyUnknownAuthorPlaceholder("Невідомий автор")).isTrue();
        assertThat(Book.isLegacyUnknownAuthorPlaceholder("Без автора")).isTrue();
        assertThat(Book.isLegacyUnknownAuthorPlaceholder("Реальний Автор")).isFalse();
    }
}
