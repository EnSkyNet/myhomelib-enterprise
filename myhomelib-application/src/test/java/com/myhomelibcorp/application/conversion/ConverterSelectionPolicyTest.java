package com.myhomelibcorp.application.conversion;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.domain.model.book.Book;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConverterSelectionPolicyTest {

    @Test
    void calibreModeAllowsCoreAndPluginCalibreButNotToolchain() {
        assertThat(ConverterSelectionPolicy.allowed(converter("calibre-core", 100), "CALIBRE")).isTrue();
        assertThat(ConverterSelectionPolicy.allowed(converter("plugin:calibre-cli", 100), "CALIBRE")).isTrue();
        assertThat(ConverterSelectionPolicy.allowed(converter("myhomelib-toolchain", 500), "CALIBRE")).isFalse();
    }

    @Test
    void manualModeAllowsOnlyDirectAndExplicitExternalProviders() {
        assertThat(ConverterSelectionPolicy.allowed(converter("direct-copy", 1000), "MANUAL")).isTrue();
        assertThat(ConverterSelectionPolicy.allowed(converter("external:custom", 300), "MANUAL")).isTrue();
        assertThat(ConverterSelectionPolicy.allowed(converter("myhomelib-toolchain", 500), "MANUAL")).isFalse();
        assertThat(ConverterSelectionPolicy.allowed(converter("plugin:generic", 0), "MANUAL")).isFalse();
        assertThat(ConverterSelectionPolicy.allowed(converter("plugin:calibre-cli", 100), "MANUAL")).isFalse();
    }

    @Test
    void toolchainModeKeepsDirectCopyAheadOfToolchainOnly() {
        assertThat(ConverterSelectionPolicy.allowed(converter("direct-copy", 1000), "TOOLCHAIN")).isTrue();
        assertThat(ConverterSelectionPolicy.allowed(converter("myhomelib-toolchain", 500), "TOOLCHAIN")).isTrue();
        assertThat(ConverterSelectionPolicy.allowed(converter("external:custom", 300), "TOOLCHAIN")).isFalse();
        assertThat(ConverterSelectionPolicy.allowed(converter("calibre-core", 100), "TOOLCHAIN")).isFalse();
    }

    private static BookConverter converter(String id, int priority) {
        return new BookConverter() {
            @Override public String id() { return id; }
            @Override public int priority() { return priority; }
            @Override public boolean supports(Book book) { return true; }
            @Override public String getTargetExtension() { return ".epub"; }
            @Override public String getFormatName() { return "epub"; }
            @Override public void convert(Book book, InputStream sourceStream, Path targetFile) { }
        };
    }
}
