package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MainToolbarLayoutContractTest {

    @Test
    void mainToolbarUsesWrappingPaneInsteadOfSingleLineToolbar() throws IOException {
        try (var stream = getClass().getResourceAsStream("/view/MainView.fxml")) {
            assertThat(stream).isNotNull();
            String fxml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(fxml).contains("<FlowPane fx:id=\"mainToolbar\"");
            assertThat(fxml).contains("styleClass=\"main-toolbar-wrap\"");
            assertThat(fxml).contains("fx:id=\"themeButton\"");
            assertThat(fxml).contains("onAction=\"#handleCycleApplicationTheme\"");
            assertThat(fxml).doesNotContain("<ToolBar styleClass=\"main-toolbar-wrap\"");
        }
    }
}
