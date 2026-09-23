package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExternalCommandBookConverterSecurityTest {
    @TempDir Path temp;

    @Test
    void killsExternalConverterWhenLiveOutputExceedsConfiguredLimit() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        String previous = System.getProperty("myhomelib.dataDir");
        Path script = temp.resolve("oversize-converter.sh");
        Files.writeString(script, "#!/bin/sh\nwhile :; do printf '0123456789abcdef0123456789abcdef' >> \"$2\"; sleep 0.01; done\n");
        assertTrue(script.toFile().setExecutable(true, true) || Files.isExecutable(script));
        try {
            System.setProperty("myhomelib.dataDir", temp.resolve("data").toString());
            MemorySettings settings = new MemorySettings();
            settings.put("converter.pdf.command", script + " %SRC% %DST%");
            settings.put("converter.timeoutSeconds", "30");
            PdfExternalBookConverter converter = new PdfExternalBookConverter(settings);
            Path target = temp.resolve("result.pdf");

            IllegalStateException error = assertThrows(IllegalStateException.class, () -> converter.convert(
                    new BookConversionContext(book(), "fb2", "pdf",
                            new ByteArrayInputStream("source".getBytes()), target, () -> false, 128)));

            assertTrue(error.getMessage().contains("ліміт"), error::getMessage);
            assertFalse(Files.exists(target), "Oversized external output must never be published");
        } finally {
            if (previous == null) System.clearProperty("myhomelib.dataDir");
            else System.setProperty("myhomelib.dataDir", previous);
        }
    }

    private static Book book() {
        return Book.builder().id(BookId.generate()).title("Book")
                .file(new BookFile("book.fb2", "", "", 6, "/library")).build();
    }

    private static final class MemorySettings implements ApplicationSettingsPort {
        private final Map<String, String> values = new HashMap<>();
        @Override public String get(String key, String defaultValue) { return values.getOrDefault(key, defaultValue); }
        @Override public void put(String key, String value) { values.put(key, value); }
        @Override public void remove(String key) { values.remove(key); }
        @Override public Map<String, String> findByPrefix(String prefix) {
            Map<String, String> result = new HashMap<>();
            values.forEach((key, value) -> { if (key.startsWith(prefix)) result.put(key, value); });
            return result;
        }
    }
}
