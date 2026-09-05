package com.myhomelibcorp.application.imports.detector;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class BookFormatDetectorLocaleTest {

    @Test
    void detectsAsciiExtensionsIndependentlyOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            BookFormatDetector detector = new BookFormatDetector();

            assertThat(detector.detect(Path.of("LIBRARY.INPX"))).isEqualTo(BookFormatDetector.Format.INPX);
            assertThat(detector.detect(Path.of("BOOK.FB2"))).isEqualTo(BookFormatDetector.Format.FB2);
            assertThat(detector.detect(Path.of("ARCHIVE.ZIP"))).isEqualTo(BookFormatDetector.Format.ZIP);
            assertThat(detector.detect(Path.of("BOOK.EPUB"))).isEqualTo(BookFormatDetector.Format.EPUB);
        } finally {
            Locale.setDefault(previous);
        }
    }
}
