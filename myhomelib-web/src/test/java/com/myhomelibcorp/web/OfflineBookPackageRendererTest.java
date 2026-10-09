package com.myhomelibcorp.web;

import com.myhomelibcorp.application.webreader.WebReaderChapter;
import com.myhomelibcorp.application.webreader.WebReaderDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfflineBookPackageRendererTest {
    private final OfflineBookPackageRenderer renderer = new OfflineBookPackageRenderer();

    @Test void manifestUsesStableIdentityRevisionAndServerRenderedChapterProjectionWithoutRawContent() {
        WebReaderDocument doc = document("book / 1", "secret chapter text");

        String manifest = renderer.manifest(doc);
        String revision = renderer.revision(doc);

        assertThat(revision).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(manifest)
                .contains("\"schemaVersion\":1", "\"bookId\":\"book / 1\"", "\"revision\":\"" + revision + "\"",
                        "\"detailUrl\":\"/web/books/book%20%2F%201\"",
                        "\"/web/read/book%20%2F%201?chapter=0\"",
                        "\"/web/read/book%20%2F%201?chapter=1\"")
                .doesNotContain("secret chapter text", "second raw text", "download");
    }

    @Test void revisionIsDeterministicAndChangesWhenProjectedContentChanges() {
        WebReaderDocument first = document("id", "alpha");
        WebReaderDocument same = document("id", "alpha");
        WebReaderDocument changed = document("id", "beta");

        assertThat(renderer.revision(first)).isEqualTo(renderer.revision(same));
        assertThat(renderer.revision(changed)).isNotEqualTo(renderer.revision(first));
    }

    @Test void unsupportedOrEmptyDocumentCannotProduceOfflinePackage() {
        WebReaderDocument unsupported = new WebReaderDocument("id", "Book", "pdf", false, "unsupported",
                List.of(), 0, 0, 0, 0);
        WebReaderDocument empty = new WebReaderDocument("id", "Book", "epub", true, "",
                List.of(), 0, 0, 0, 0);

        assertThatThrownBy(() -> renderer.manifest(unsupported)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> renderer.manifest(empty)).isInstanceOf(IllegalArgumentException.class);
    }

    private static WebReaderDocument document(String id, String firstText) {
        return new WebReaderDocument(id, "Book", "epub", true, "", List.of(
                new WebReaderChapter(0, "c1", "First", 0, 10, firstText),
                new WebReaderChapter(1, "c2", "Second", 11, 20, "second raw text")
        ), 0, 0, 0, 0);
    }
}
