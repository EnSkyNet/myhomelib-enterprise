package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MainToolbarLayoutContractTest {

    @Test
    void mainToolbarUsesSingleRowAdaptiveLayoutWithOverflowActions() throws IOException {
        try (var stream = getClass().getResourceAsStream("/view/MainView.fxml")) {
            assertThat(stream).isNotNull();
            String fxml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(fxml).contains("<HBox fx:id=\"mainToolbar\"");
            assertThat(fxml).contains("styleClass=\"main-toolbar-wrap\"");
            assertThat(fxml).contains("HBox.hgrow=\"ALWAYS\"");
            assertThat(fxml).contains("<MenuButton text=\"⋮\"");
            assertThat(fxml).contains("onAction=\"#handleCycleApplicationTheme\"");
            assertThat(fxml).doesNotContain("<FlowPane fx:id=\"mainToolbar\"");
            assertThat(fxml).doesNotContain("fx:id=\"themeButton\"");
        }
    }
}
