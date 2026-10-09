package com.myhomelibcorp.ui.reader;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderArchiveCompatibilityUiContractTest {

    @Test
    void selectedArchiveMemberUsesSingleResolutionStreamingMaterialization() throws Exception {
        String controller = controllerSource();
        String resolver = resolverSource();

        assertThat(controller).contains("resolveReadableReaderSource(projectedBook, targetArtifact, bookResourcePort)");
        assertThat(controller).contains("return ReaderSourceResolver.resolve(book, requestedArtifactId, bookResourcePort)");
        assertThat(resolver).contains("bookResourcePort.locateBookContainer(candidate)");
        assertThat(resolver).doesNotContain("locateBookFile(candidate)");
        assertThat(resolver).contains("bookResourcePort.materializeArchiveBookEntry(");
        assertThat(resolver).contains("ArchiveSafetyLimits.MAX_ENTRY_BYTES");
        assertThat(resolver).contains("() -> Thread.currentThread().isInterrupted()");
        assertThat(resolver).doesNotContain("bookResourcePort.readBookData(book)");
        assertThat(resolver).doesNotContain("bookResourcePort.readArchiveEntry(physicalPath, selectedEntry)");
        assertThat(resolver).doesNotContain("stream.transferTo(");
    }

    @Test
    void readerMaterializedTempLifecycleCoversFailureSwitchAbandonAndDispose() throws Exception {
        String controller = controllerSource();
        String resolver = resolverSource();

        assertThat(resolver).contains("if (!success) Files.deleteIfExists(temp)");
        assertThat(controller).contains("prepared.closeAbandoned()");
        assertThat(controller).contains("cleanupMaterializedBookFile();");
        assertThat(controller).contains("task.cancel(true)");
        assertThat(controller).contains("deleteTemp(temporaryPath)");
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

    private static String resolverSource() throws Exception {
        return Files.readString(Path.of(
                "../myhomelib-application/src/main/java/com/myhomelibcorp/application/reader/ReaderSourceResolver.java"));
    }
}
