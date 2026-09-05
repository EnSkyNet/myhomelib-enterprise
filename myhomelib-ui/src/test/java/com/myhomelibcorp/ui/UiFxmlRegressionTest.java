package com.myhomelibcorp.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiFxmlRegressionTest {

    @Test
    void mainToolbarMustRemainSingleRowAndExposeSearchClearAction() throws IOException {
        String fxml = resource("/view/MainView.fxml");

        assertTrue(fxml.contains("<ToolBar styleClass=\"main-toolbar-wrap\">"),
                "Main toolbar must use JavaFX ToolBar so sidebar width changes cannot wrap it to another row");
        assertFalse(fxml.contains("<FlowPane hgap=\"4\" vgap=\"4\" alignment=\"CENTER_LEFT\" styleClass=\"main-toolbar-wrap\">"),
                "Wrapping FlowPane reintroduces top-toolbar height jumps when the right sidebar is toggled");
        assertTrue(fxml.contains("onAction=\"#handleClearSearch\""),
                "Global search must expose an explicit clear button");
    }

    @Test
    void collectionWizardIndicatorsMustUseDynamicStyleClassesAndOnlineUpdateUrlField() throws IOException {
        String fxml = resource("/view/collection-wizard.fxml");

        for (int step = 1; step <= 3; step++) {
            assertTrue(fxml.contains("text=\"Крок " + step + "\" styleClass=\"wizard-step-indicator\""),
                    "Each wizard step indicator must share the dynamic style class");
        }
        assertTrue(fxml.contains("fx:id=\"catalogUpdateUrlField\""),
                "Online collection wizard must include the INPX update URL field");
        assertTrue(fxml.contains("text=\"URL INPX для оновлення:\""),
                "Online INPX field must have a clear label");
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = UiFxmlRegressionTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
