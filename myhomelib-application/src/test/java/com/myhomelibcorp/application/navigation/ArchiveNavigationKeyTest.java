package com.myhomelibcorp.application.navigation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchiveNavigationKeyTest {

    @Test
    void encodedKeyRoundTripsUnicodeAndWindowsPaths() {
        ArchiveNavigationKey source = new ArchiveNavigationKey(
                "D:\\Бібліотека",
                "D:\\Бібліотека\\Архіви\\books.zip");

        assertThat(ArchiveNavigationKey.decode(source.encode())).isEqualTo(source);
    }

    @Test
    void malformedKeyIsRejected() {
        assertThatThrownBy(() -> ArchiveNavigationKey.decode("not-valid-base64!"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
