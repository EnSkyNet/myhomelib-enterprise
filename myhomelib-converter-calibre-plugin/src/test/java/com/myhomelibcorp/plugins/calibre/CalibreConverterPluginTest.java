package com.myhomelibcorp.plugins.calibre;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.plugin.api.BookConverter;
import com.myhomelibcorp.plugin.api.PluginApiVersion;
import com.myhomelibcorp.plugin.api.PluginPermission;
import com.myhomelibcorp.plugin.api.PluginService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void legacyFiveMinuteRuntimePropertyUsesLongConversionDefault() throws Exception {
        String previous = System.getProperty("myhomelib.calibre.timeoutSeconds");
        try {
            System.setProperty("myhomelib.calibre.timeoutSeconds", "300");
            var method = CalibreConverterPlugin.CalibreConverter.class.getDeclaredMethod("configuredTimeout");
            method.setAccessible(true);
            assertThat(method.invoke(null)).isEqualTo(Duration.ofMinutes(30));
        } finally {
            if (previous == null) System.clearProperty("myhomelib.calibre.timeoutSeconds");
            else System.setProperty("myhomelib.calibre.timeoutSeconds", previous);
        }
    }

    @Test
    void successfulProcessNeverDeletesOrReplacesAnExistingTargetOnPosix() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path script = Files.createTempFile("ebook-convert-plugin-ok-", "");
        Files.writeString(script, "#!/bin/sh\ncp -- \"$1\" \"$2\"\n");
        script.toFile().setExecutable(true, true);
        Path target = Files.createTempFile("calibre-existing-target-", ".epub");
        Files.writeString(target, "original");
        String previous = System.getProperty("myhomelib.calibre.executable");
        try {
            System.setProperty("myhomelib.calibre.executable", script.toString());
            BookConverter converter = (BookConverter) plugin.services().get(PluginService.BOOK_CONVERTER);
            assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> converter.convert(
                    new BookConversionContext(book(), "fb2", "epub", new ByteArrayInputStream("new".getBytes()),
                            target, () -> false, 1024)));
            assertThat(Files.readString(target)).isEqualTo("original");
        } finally {
            if (previous == null) System.clearProperty("myhomelib.calibre.executable");
            else System.setProperty("myhomelib.calibre.executable", previous);
            Files.deleteIfExists(script);
            Files.deleteIfExists(target);
        }
    }

    @Test
    void killsPluginProcessWhenLiveOutputExceedsLimitOnPosix() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path script = Files.createTempFile("ebook-convert-plugin-limit-", "");
        Files.writeString(script, "#!/bin/sh\nwhile :; do printf '0123456789abcdef0123456789abcdef' >> \"$2\"; sleep 0.01; done\n");
        script.toFile().setExecutable(true, true);
        Path target = script.resolveSibling("plugin-oversized.epub");
        String previous = System.getProperty("myhomelib.calibre.executable");
        try {
            System.setProperty("myhomelib.calibre.executable", script.toString());
            BookConverter converter = (BookConverter) plugin.services().get(PluginService.BOOK_CONVERTER);
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> converter.convert(
                    new BookConversionContext(book(), "fb2", "epub", new ByteArrayInputStream("source".getBytes()),
                            target, () -> false, 128)));
            assertThat(error.getMessage()).contains("limit");
            assertThat(Files.exists(target)).isFalse();
        } finally {
            if (previous == null) System.clearProperty("myhomelib.calibre.executable");
            else System.setProperty("myhomelib.calibre.executable", previous);
            Files.deleteIfExists(script);
            Files.deleteIfExists(target);
        }
    }

    @Test
    void processTreeTerminationWaitsForForciblyKilledDescendants() throws Exception {
        DelayedTerminationHandle child = new DelayedTerminationHandle(4202, List.of(), true);
        DelayedTerminationHandle root = new DelayedTerminationHandle(4201, List.of(child), false);
        DelayedTerminationProcess process = new DelayedTerminationProcess(root);

        var method = CalibreConverterPlugin.CalibreConverter.class
                .getDeclaredMethod("terminateProcessTree", Process.class);
        method.setAccessible(true);
        method.invoke(null, process);

        assertThat(root.isAlive()).isFalse();
        assertThat(child.isAlive())
                .as("Calibre termination must not return while a forcibly-killed child is still alive")
                .isFalse();
    }

    private static final class DelayedTerminationProcess extends Process {
        private final DelayedTerminationHandle handle;

        private DelayedTerminationProcess(DelayedTerminationHandle handle) { this.handle = handle; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException {
            while (handle.isAlive()) Thread.sleep(5);
            return 0;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (handle.isAlive() && System.nanoTime() < deadline) Thread.sleep(5);
            return !handle.isAlive();
        }
        @Override public int exitValue() {
            if (handle.isAlive()) throw new IllegalThreadStateException("running");
            return 0;
        }
        @Override public void destroy() { handle.destroy(); }
        @Override public Process destroyForcibly() { handle.destroyForcibly(); return this; }
        @Override public boolean isAlive() { return handle.isAlive(); }
        @Override public ProcessHandle toHandle() { return handle; }
    }

    private static final class DelayedTerminationHandle implements ProcessHandle {
        private final long pid;
        private final List<ProcessHandle> descendants;
        private final boolean delayedForcibleExit;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final CompletableFuture<ProcessHandle> exited = new CompletableFuture<>();

        private DelayedTerminationHandle(long pid, List<? extends ProcessHandle> descendants, boolean delayedForcibleExit) {
            this.pid = pid;
            this.descendants = List.copyOf(descendants);
            this.delayedForcibleExit = delayedForcibleExit;
        }
        @Override public long pid() { return pid; }
        @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
        @Override public java.util.stream.Stream<ProcessHandle> children() { return descendants.stream(); }
        @Override public java.util.stream.Stream<ProcessHandle> descendants() { return descendants.stream(); }
        @Override public Info info() { throw new UnsupportedOperationException(); }
        @Override public CompletableFuture<ProcessHandle> onExit() { return exited; }
        @Override public boolean supportsNormalTermination() { return true; }
        @Override public boolean destroy() {
            if (!delayedForcibleExit) markExited();
            return true;
        }
        @Override public boolean destroyForcibly() {
            if (!delayedForcibleExit) markExited();
            else Thread.startVirtualThread(() -> {
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                markExited();
            });
            return true;
        }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int compareTo(ProcessHandle other) { return Long.compare(pid, other.pid()); }
        @Override public int hashCode() { return Long.hashCode(pid); }
        @Override public boolean equals(Object other) { return other instanceof ProcessHandle handle && handle.pid() == pid; }
        private void markExited() { if (alive.compareAndSet(true, false)) exited.complete(this); }
    }

    private static Book book() {
        return Book.builder().id(BookId.generate()).title("Book")
                .file(new BookFile("book.fb2", "", "", 6, "/library")).build();
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
