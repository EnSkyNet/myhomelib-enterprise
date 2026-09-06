package com.myhomelibcorp.shared.format;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SupportedFormatRegistryTest {
    private final SupportedFormatRegistry registry = SupportedFormatRegistry.standard();

    @Test
    void detectsRepresentativeFormatsIncludingCompoundExtensionsLocaleIndependently() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(registry.searchFormat("BOOK.FB2")).isEqualTo("FB2");
            assertThat(registry.searchFormat("report.DOCX")).isEqualTo("DOCX");
            assertThat(registry.searchFormat("page.HTML")).isEqualTo("HTML");
            assertThat(registry.searchFormat("reader.AZW3")).isEqualTo("AZW3");
            assertThat(registry.searchFormat("scan.DJVU")).isEqualTo("DJVU");
            assertThat(registry.searchFormat("book.fb2.zip")).isEqualTo("ZIP");
            assertThat(registry.searchFormat("backup.tar.gz")).isEqualTo("TAR");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void exposesEveryImportSupportedExtensionForChooserGeneration() {
        var expected = registry.extensions(SupportedFormat::importSupported);
        var patterns = registry.chooserPatterns(SupportedFormat::importSupported);

        assertThat(patterns).hasSameSizeAs(expected);
        assertThat(patterns).contains("*.pdf", "*.djvu", "*.mobi", "*.azw3", "*.docx", "*.rtf", "*.html", "*.chm");
        assertThat(registry.isImportSupported(Path.of("novel.epub"))).isTrue();
        assertThat(registry.isImportSupported(Path.of("unknown.exe"))).isFalse();
    }

    @Test
    void capabilityMatrixKeepsReaderSubsetExplicit() {
        assertThat(registry.byId("fb2")).get().extracting(SupportedFormat::readerSupported).isEqualTo(true);
        assertThat(registry.byId("epub")).get().extracting(SupportedFormat::fullTextSupported).isEqualTo(true);
        assertThat(registry.byId("pdf")).get().extracting(SupportedFormat::readerSupported).isEqualTo(false);
        assertThat(registry.byId("docx")).get().extracting(SupportedFormat::importMode)
                .isEqualTo(SupportedFormat.ImportMode.GENERIC);
    }
}
