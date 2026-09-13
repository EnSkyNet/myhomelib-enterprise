package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderArchiveCompatibilityUiContractTest {

    @Test
    void selectedArchiveMemberUsesSingleResolutionStreamingMaterialization() throws Exception {
        String source = controllerSource();

        assertThat(source).contains("bookResourcePort.locateBookContainer(book)");
        assertThat(source).contains("bookResourcePort.materializeArchiveBookEntry(");
        assertThat(source).contains("ArchiveSafetyLimits.MAX_ENTRY_BYTES");
        assertThat(source).contains("() -> Thread.currentThread().isInterrupted()");
        assertThat(source).doesNotContain("bookResourcePort.readBookData(book)");
        assertThat(source).doesNotContain("bookResourcePort.readArchiveEntry(physicalPath, selectedEntry)");
        assertThat(source).doesNotContain("stream.transferTo(");
    }

    @Test
    void readerMaterializedTempLifecycleCoversFailureSwitchAbandonAndDispose() throws Exception {
        String source = controllerSource();

        assertThat(source).contains("if (!success) Files.deleteIfExists(temp)");
        assertThat(source).contains("prepared.closeAbandoned()");
        assertThat(source).contains("cleanupMaterializedBookFile();");
        assertThat(source).contains("task.cancel(true)");
        assertThat(source).contains("deleteTemp(temporaryPath)");
    }

    @Test
    void readerOpenTimingSeparatesResolveMaterializeParseAndRenderReadyWithoutContentPayload() throws Exception {
        String source = controllerSource();

        assertThat(source).contains("reader_open_timing resolve_ms={} materialize_ms={} parse_ms={} render_ready_ms={} total_ms={} format={} size_bytes={}");
        assertThat(source).contains("new ReaderOpenTiming(openStarted, resolveNanos, materializeNanos, parseNanos,");
        assertThat(source).contains("bytes, source.extension())");
        assertThat(source).doesNotContain("reader_open_timing content=");
    }

    private static String controllerSource() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/reader/NewReaderWorkspaceController.java"));
    }
}
