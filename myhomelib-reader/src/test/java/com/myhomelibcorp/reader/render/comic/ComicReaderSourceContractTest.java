package com.myhomelibcorp.reader.render.comic;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ComicReaderSourceContractTest {
    @Test
    void rendererKeepsArchivePagesLazyAndHeavyWorkOffFxThread() throws Exception {
        String view = Files.readString(Path.of("src/main/java/com/myhomelibcorp/reader/render/comic/ComicReaderView.java"));
        String session = Files.readString(Path.of("src/main/java/com/myhomelibcorp/reader/render/comic/ComicDocumentSession.java"));

        assertThat(view).contains("Executors.newSingleThreadExecutor", "submitTrackedRender", "Platform.runLater",
                "AtomicLong generation", "cancelOutstandingRenders", "task.cancel(true)",
                "dualPageMode", "rtlMode", "continuousList", "thumbnails");
        assertThat(session).contains("source.listPageEntries()", "source.openPage(entry)", "ImageReadParam",
                "setSourceSubsampling", "MAX_PAGE_BYTES", "MAX_PAGE_PIXELS", "MAX_CACHE_BYTES", "LinkedHashMap");
    }

    @Test
    void comicModesAndProgressAreExplicitReaderFeatures() throws Exception {
        String view = Files.readString(Path.of("src/main/java/com/myhomelibcorp/reader/render/comic/ComicReaderView.java"));
        assertThat(view).contains("ui.reader.comic.fit_width", "ui.reader.comic.fit_page",
                "ui.reader.comic.continuous", "ui.reader.comic.dual_page", "ui.reader.comic.rtl",
                "ui.reader.comic.thumbnails", "currentPosition()", "goToPosition", "progressPercent()");
    }
}
