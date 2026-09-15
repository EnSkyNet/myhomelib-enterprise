package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CalibreCliBookConverterTest {
    @TempDir Path temp;
    private String previousDataDir;

    @AfterEach
    void restoreDataDir() {
        if (previousDataDir == null) System.clearProperty("myhomelib.dataDir");
        else System.setProperty("myhomelib.dataDir", previousDataDir);
    }

    @Test
    void remainsUnavailableWhenCalibreCannotBeResolved() {
        MemorySettings settings = new MemorySettings();
        CalibreCliBookConverter converter = new CalibreCliBookConverter(settings, "epub", ".epub",
                configured -> Optional.empty(), (argv, cwd, cancelled, timeout) -> fail("runner must not execute"));

        assertFalse(converter.isAvailable());
        assertFalse(converter.supports(book("book.fb2")));
        assertEquals("calibre-cli:epub", converter.id());
    }

    @Test
    void exposesCuratedProviderNeutralCapabilities() {
        CalibreCliBookConverter converter = availableConverter("azw3", ".azw3", (argv, cwd, cancelled, timeout) ->
                new CalibreCliBookConverter.CommandResult(0, ""));

        var capability = converter.capabilities().iterator().next();
        assertTrue(capability.supportsSource("fb2"));
        assertTrue(capability.supportsSource("DOCX"));
        assertTrue(capability.produces("azw3"));
        assertEquals(".azw3", capability.targetExtension());
        assertFalse(capability.supportsSource("exe"));
    }

    @Test
    void executesWithSeparateArgumentsAndApplicationOwnedSandbox() throws Exception {
        previousDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("data root with spaces").toString());
        AtomicReference<List<String>> args = new AtomicReference<>();
        AtomicReference<Path> cwd = new AtomicReference<>();

        CalibreCliBookConverter converter = availableConverter("epub", ".epub", (argv, workingDirectory, cancelled, timeout) -> {
            args.set(List.copyOf(argv));
            cwd.set(workingDirectory);
            assertEquals(Duration.ofSeconds(300), timeout);
            assertTrue(argv.get(1).endsWith(".fb2"));
            assertTrue(Path.of(argv.get(1)).startsWith(workingDirectory));
            Files.writeString(Path.of(argv.get(2)), "converted");
            return new CalibreCliBookConverter.CommandResult(0, "conversion ok");
        });

        Path target = temp.resolve("out folder/book.epub");
        Book book = book("unsafe name; $(echo injected).fb2");
        converter.convert(new BookConversionContext(book, "fb2", "epub",
                new ByteArrayInputStream("source".getBytes()), target, () -> false, 1024));

        assertEquals(3, args.get().size(), "No shell/template argument splitting is allowed");
        assertEquals(Path.of("/fake/ebook-convert").toString(), args.get().get(0));
        assertEquals(target.toAbsolutePath().normalize().toString(), args.get().get(2));
        assertTrue(cwd.get().endsWith("cache/calibre"));
        assertEquals("converted", Files.readString(target));
        assertFalse(Files.exists(Path.of(args.get().get(1))), "Temporary source must be deleted");
    }

    @Test
    void nonZeroExitCapturesBoundedDiagnosticAndDeletesSource() throws Exception {
        previousDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("data").toString());
        AtomicReference<Path> source = new AtomicReference<>();
        CalibreCliBookConverter converter = availableConverter("pdf", ".pdf", (argv, cwd, cancelled, timeout) -> {
            source.set(Path.of(argv.get(1)));
            return new CalibreCliBookConverter.CommandResult(7, "bad input\nmore details");
        });

        var error = assertThrows(IllegalStateException.class, () -> converter.convert(
                new BookConversionContext(book("book.fb2"), "fb2", "pdf",
                        new ByteArrayInputStream("source".getBytes()), temp.resolve("book.pdf"), () -> false, 1024)));

        assertTrue(error.getMessage().contains("code 7"));
        assertTrue(error.getMessage().contains("bad input"));
        assertFalse(Files.exists(source.get()));
    }

    @Test
    void cancellationBeforeLaunchDoesNotRunProcess() {
        AtomicBoolean ran = new AtomicBoolean();
        CalibreCliBookConverter converter = availableConverter("epub", ".epub", (argv, cwd, cancelled, timeout) -> {
            ran.set(true);
            return new CalibreCliBookConverter.CommandResult(0, "");
        });

        assertThrows(CancellationException.class, () -> converter.convert(
                new BookConversionContext(book("book.fb2"), "fb2", "epub",
                        new ByteArrayInputStream("source".getBytes()), temp.resolve("book.epub"), () -> true, 1024)));
        assertFalse(ran.get());
    }

    @Test
    void configuredExecutableRunsThroughRealProcessBuilderOnPosix() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        previousDataDir = System.getProperty("myhomelib.dataDir");
        System.setProperty("myhomelib.dataDir", temp.resolve("real-run-data").toString());

        Path fakeCalibre = temp.resolve("ebook-convert");
        Files.writeString(fakeCalibre, "#!/bin/sh\ncp -- \"$1\" \"$2\"\necho fake-calibre-ok\n");
        assertTrue(fakeCalibre.toFile().setExecutable(true, true) || Files.isExecutable(fakeCalibre));

        MemorySettings settings = new MemorySettings();
        settings.put(CalibreCliBookConverter.EXECUTABLE_SETTING, fakeCalibre.toString());
        CalibreCliBookConverter converter = new CalibreCliBookConverter(settings, "epub", ".epub");
        Path target = temp.resolve("real output/book.epub");

        assertTrue(converter.isAvailable());
        converter.convert(new BookConversionContext(book("book.fb2"), "fb2", "epub",
                new ByteArrayInputStream("real-run".getBytes()), target, () -> false, 1024));
        assertEquals("real-run", Files.readString(target));
    }

    @Test
    void rejectsTargetMismatch() {
        CalibreCliBookConverter converter = availableConverter("epub", ".epub", (argv, cwd, cancelled, timeout) ->
                new CalibreCliBookConverter.CommandResult(0, ""));
        assertThrows(IllegalArgumentException.class, () -> converter.convert(
                new BookConversionContext(book("book.fb2"), "fb2", "pdf",
                        new ByteArrayInputStream(new byte[1]), temp.resolve("book.pdf"), () -> false, 1024)));
    }

    private CalibreCliBookConverter availableConverter(String format, String extension,
                                                        CalibreCliBookConverter.CommandRunner runner) {
        MemorySettings settings = new MemorySettings();
        return new CalibreCliBookConverter(settings, format, extension,
                configured -> Optional.of(Path.of("/fake/ebook-convert")), runner);
    }

    private static Book book(String fileName) {
        return Book.builder()
                .id(BookId.generate())
                .title("Book")
                .file(new BookFile(fileName, "", "", 6, "/library"))
                .build();
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
