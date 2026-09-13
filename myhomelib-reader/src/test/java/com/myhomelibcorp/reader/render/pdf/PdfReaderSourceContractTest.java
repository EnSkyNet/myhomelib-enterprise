package com.myhomelibcorp.reader.render.pdf;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PdfReaderSourceContractTest {
    @Test
    void rendererKeepsHeavyWorkOffFxThreadAndGuardsStaleResults() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/myhomelibcorp/reader/render/pdf/PdfReaderView.java"));
        assertThat(source).contains("Executors.newSingleThreadExecutor", "submitTrackedRender", "Platform.runLater",
                "AtomicLong generation", "AtomicLong singleRenderGeneration", "ConcurrentHashMap.newKeySet",
                "configToken != generation.get()", "task.cancel(true)", "cancelOutstandingRenders", "session != current");
    }

    @Test
    void sessionHasExplicitMemoryAndRasterBounds() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/myhomelibcorp/reader/render/pdf/PdfDocumentSession.java"));
        assertThat(source).contains("DEFAULT_CACHE_BYTES", "MAX_RASTER_PIXELS", "evictIfNeeded", "LinkedHashMap",
                "Loader.loadPDF", "IOUtils.createTempFileOnlyStreamCache", "setSubsamplingAllowed(true)",
                "readPageSizes(doc)", "return pageSizes.get(pageIndex)", "PDF page exceeds the raster safety limit");
    }
    @Test
    void textLayerFeaturesStayBoundedCancellableAndOcrFree() throws Exception {
        String session = Files.readString(Path.of("src/main/java/com/myhomelibcorp/reader/render/pdf/PdfDocumentSession.java"));
        String view = Files.readString(Path.of("src/main/java/com/myhomelibcorp/reader/render/pdf/PdfReaderView.java"));
        assertThat(session).contains("PDFTextStripper", "MAX_SEARCH_RESULTS", "MAX_OUTLINE_ENTRIES",
                "MAX_OUTLINE_DEPTH", "checkInterrupted()", "textLayerDetected");
        assertThat(session.toLowerCase()).doesNotContain("tesseract", "ocrmypdf");
        assertThat(view).contains("searchTextAsync", "submitTrackedDocumentTask",
                "cancelOutstandingDocumentTasks", "outlineEntries()", "goToPosition");
    }

}
