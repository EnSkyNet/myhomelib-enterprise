package com.myhomelibcorp.reader.layout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HyphenationServiceTest {
    private final HyphenationService service = new HyphenationService();

    @Test
    void loadsLanguageSpecificDictionaryEntries() {
        assertThat(service.candidates("бібліотека", "uk-UA")).contains(3, 6);
        assertThat(service.candidates("navigation", "en-US")).isNotEmpty();
        assertThat(service.candidates("библиотека", "ru")).isNotEmpty();
        assertThat(service.candidates("библиотека", "bg")).isNotEmpty();
    }

    @Test
    void keepsSafePrefixAndSuffixForUnknownWords() {
        var positions = service.candidates("електромагнітний", "uk");
        assertThat(positions).allMatch(p -> p >= 2 && p <= "електромагнітний".length() - 2);
    }
}
