package com.myhomelibcorp.ui.details;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BookDetailsResponsiveGalleryContractTest {
    @Test
    void galleryFitsVisibleScreenAndImageTracksResizableViewport() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java"),
                StandardCharsets.UTF_8);

        assertThat(source).contains("dialog.setResizable(true)");
        assertThat(source).contains("getVisualBounds()");
        assertThat(source).contains("view.fitWidthProperty().bind(");
        assertThat(source).contains("view.fitHeightProperty().bind(");
        assertThat(source).doesNotContain("view.setFitWidth(720)");
        assertThat(source).doesNotContain("view.setFitHeight(530)");
    }
}
