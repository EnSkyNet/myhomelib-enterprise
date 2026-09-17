package com.myhomelibcorp.plugins.calibre;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.plugin.api.BookConverter;
import com.myhomelibcorp.plugin.api.PluginApiVersion;
import com.myhomelibcorp.plugin.api.PluginPermission;
import com.myhomelibcorp.plugin.api.PluginService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CalibreConverterPluginTest {
    private final CalibreConverterPlugin plugin = new CalibreConverterPlugin();

    @Test
    void manifestMatchesInstallableConversionPluginContract() {
        var manifest = plugin.manifest();
        assertThat(manifest.pluginId()).isEqualTo("converter.calibre");
        assertThat(manifest.apiRange().min()).isEqualTo(new PluginApiVersion(1, 4));
        assertThat(manifest.apiRange().max()).isEqualTo(new PluginApiVersion(1, 99));
        assertThat(manifest.services()).containsExactly(PluginService.BOOK_CONVERTER);
        assertThat(manifest.permissions()).containsExactlyInAnyOrder(
                PluginPermission.FILESYSTEM_READ, PluginPermission.FILESYSTEM_WRITE,
                PluginPermission.EXTERNAL_PROCESS_EXECUTION);
    }

    @Test
    void configuredExecutablePropertyIsSharedWithDesktopSettings() throws Exception {
        Path fake = Files.createTempFile("ebook-convert-test-", System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "");
        String previous = System.getProperty("myhomelib.calibre.executable");
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) fake.toFile().setExecutable(true, true);
            System.setProperty("myhomelib.calibre.executable", fake.toString());
            BookConverter converter = (BookConverter) plugin.services().get(PluginService.BOOK_CONVERTER);
            assertThat(converter.isAvailable()).isTrue();
        } finally {
            if (previous == null) System.clearProperty("myhomelib.calibre.executable");
            else System.setProperty("myhomelib.calibre.executable", previous);
            Files.deleteIfExists(fake);
        }
    }

    @Test
    void exposesUsefulMultiFormatConversionCapabilities() {
        Object service = plugin.services().get(PluginService.BOOK_CONVERTER);
        assertThat(service).isInstanceOf(BookConverter.class);
        BookConverter converter = (BookConverter) service;
        Set<String> targets = converter.capabilities().stream()
                .map(BookConversionCapability::targetFormat).collect(java.util.stream.Collectors.toSet());
        assertThat(targets).contains("epub", "azw3", "mobi", "pdf", "fb2", "txt", "docx", "rtf", "lrf", "kepub");
        assertThat(converter.capabilities()).allSatisfy(capability ->
                assertThat(capability.sourceFormats()).contains("fb2", "epub", "docx", "pdf"));
    }
}
