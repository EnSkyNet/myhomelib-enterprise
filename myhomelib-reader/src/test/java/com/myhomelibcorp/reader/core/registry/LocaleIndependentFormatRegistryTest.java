package com.myhomelibcorp.reader.core.registry;

import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.format.fb2.Fb2Format;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class LocaleIndependentFormatRegistryTest {

    @Test
    void uppercaseExtensionsStayStableUnderTurkishLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            FileBookSource source = new FileBookSource(Path.of("LIBRARY.FB2"));
            DefaultBookFormatRegistry registry = new DefaultBookFormatRegistry();
            registry.register(new Fb2Format());

            assertThat(source.extension()).isEqualTo("fb2");
            assertThat(registry.findByExtension("FB2")).isPresent();
            assertThat(registry.findFormat(source)).isPresent();
        } finally {
            Locale.setDefault(previous);
        }
    }
}
