package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers optional calibre targets. All beans remain inert when ebook-convert is unavailable. */
@Configuration
public class CalibreConverterConfiguration {
    @Bean BookConverter calibreEpubConverter(ApplicationSettingsPort settings) {
        return new CalibreCliBookConverter(settings, "epub", ".epub");
    }
    @Bean BookConverter calibreMobiConverter(ApplicationSettingsPort settings) {
        return new CalibreCliBookConverter(settings, "mobi", ".mobi");
    }
    @Bean BookConverter calibreAzw3Converter(ApplicationSettingsPort settings) {
        return new CalibreCliBookConverter(settings, "azw3", ".azw3");
    }
    @Bean BookConverter calibrePdfConverter(ApplicationSettingsPort settings) {
        return new CalibreCliBookConverter(settings, "pdf", ".pdf");
    }
    @Bean BookConverter calibreFb2Converter(ApplicationSettingsPort settings) {
        return new CalibreCliBookConverter(settings, "fb2", ".fb2");
    }
    @Bean BookConverter calibreTxtConverter(ApplicationSettingsPort settings) {
        return new CalibreCliBookConverter(settings, "txt", ".txt");
    }
}
