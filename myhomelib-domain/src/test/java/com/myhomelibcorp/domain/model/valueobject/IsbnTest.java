package com.myhomelibcorp.domain.model.valueobject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsbnTest {
    @Test
    void acceptsValidChecksumsAndNormalizesSeparators() {
        assertThat(Isbn.tryParse("978-0-306-40615-7").map(Isbn::value)).contains("9780306406157");
        assertThat(Isbn.tryParse("0-306-40615-2").map(Isbn::value)).contains("0306406152");
    }

    @Test
    void blankAndInvalidIsbnAreSafeAtPersistenceBoundaries() {
        assertThat(Isbn.tryParse("")).isEmpty();
        assertThat(Isbn.tryParse("not-an-isbn")).isEmpty();
        assertThat(Isbn.tryParse("9780306406158")).isEmpty();
        assertThatThrownBy(() -> Isbn.of("9780306406158")).isInstanceOf(IllegalArgumentException.class);
    }
}
