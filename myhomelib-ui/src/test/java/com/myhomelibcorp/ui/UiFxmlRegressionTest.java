package com.myhomelibcorp.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiFxmlRegressionTest {

    @Test
    void mainToolbarMustWrapWhenActionsDoNotFitAndExposeSearchClearAction() throws IOException {
        String fxml = resource("/view/MainView.fxml");

        assertTrue(fxml.contains("<FlowPane fx:id=\"mainToolbar\""),
                "Main toolbar must use a wrapping pane so actions can flow to a second row instead of leaving the client area");
        assertTrue(fxml.contains("styleClass=\"main-toolbar-wrap\""),
                "Wrapping toolbar must keep the dedicated styling contract");
        assertFalse(fxml.contains("<ToolBar styleClass=\"main-toolbar-wrap\">"),
                "A single-line ToolBar can push actions beyond the visible client width");
        assertTrue(fxml.contains("onAction=\"#handleClearSearch\""),
                "Global search must expose an explicit clear button");
        assertTrue(fxml.contains("fx:id=\"themeButton\""),
                "Whole-application theme switch must remain reachable from the main toolbar");
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
