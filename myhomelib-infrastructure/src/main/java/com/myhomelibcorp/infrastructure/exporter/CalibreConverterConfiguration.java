package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers optional calibre targets. All beans remain inert when ebook-convert is unavailable. */
@Configuration
public class CalibreConverterConfiguration {
    @Bean BookConverter calibreEpubConverter(ApplicationSettingsPort settings) { return converter(settings, "epub"); }
    @Bean BookConverter calibreMobiConverter(ApplicationSettingsPort settings) { return converter(settings, "mobi"); }
    @Bean BookConverter calibreAzw3Converter(ApplicationSettingsPort settings) { return converter(settings, "azw3"); }
    @Bean BookConverter calibrePdfConverter(ApplicationSettingsPort settings) { return converter(settings, "pdf"); }
    @Bean BookConverter calibreFb2Converter(ApplicationSettingsPort settings) { return converter(settings, "fb2"); }
    @Bean BookConverter calibreTxtConverter(ApplicationSettingsPort settings) { return converter(settings, "txt"); }
    @Bean BookConverter calibreDocxConverter(ApplicationSettingsPort settings) { return converter(settings, "docx"); }
    @Bean BookConverter calibreHtmlzConverter(ApplicationSettingsPort settings) { return converter(settings, "htmlz"); }
    @Bean BookConverter calibreKepubConverter(ApplicationSettingsPort settings) { return converter(settings, "kepub"); }
    @Bean BookConverter calibreLitConverter(ApplicationSettingsPort settings) { return converter(settings, "lit"); }
    @Bean BookConverter calibreLrfConverter(ApplicationSettingsPort settings) { return converter(settings, "lrf"); }
    @Bean BookConverter calibrePdbConverter(ApplicationSettingsPort settings) { return converter(settings, "pdb"); }
    @Bean BookConverter calibrePmlzConverter(ApplicationSettingsPort settings) { return converter(settings, "pmlz"); }
    @Bean BookConverter calibreRbConverter(ApplicationSettingsPort settings) { return converter(settings, "rb"); }
    @Bean BookConverter calibreRtfConverter(ApplicationSettingsPort settings) { return converter(settings, "rtf"); }
    @Bean BookConverter calibreSnbConverter(ApplicationSettingsPort settings) { return converter(settings, "snb"); }
    @Bean BookConverter calibreTcrConverter(ApplicationSettingsPort settings) { return converter(settings, "tcr"); }
    @Bean BookConverter calibreTxtzConverter(ApplicationSettingsPort settings) { return converter(settings, "txtz"); }
    @Bean BookConverter calibreZipConverter(ApplicationSettingsPort settings) { return converter(settings, "zip"); }

    private static BookConverter converter(ApplicationSettingsPort settings, String format) {
        return new CalibreCliBookConverter(settings, format, "." + format);
    }
}
