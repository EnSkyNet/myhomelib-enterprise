package com.myhomelibcorp.domain.model.valueobject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageCodeTest {

    @Test
    void normalizesLanguageAndRegion() {
        assertThat(LanguageCode.of("UK").toString()).isEqualTo("uk");
        assertThat(LanguageCode.of("uk_ua").toString()).isEqualTo("uk-UA");
        assertThat(LanguageCode.of("UK-ua").toString()).isEqualTo("uk-UA");
    }

    @Test
    void rejectsInvalidLanguageCode() {
        assertThatThrownBy(() -> LanguageCode.of("unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
