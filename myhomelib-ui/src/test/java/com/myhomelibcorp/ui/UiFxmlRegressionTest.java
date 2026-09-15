package com.myhomelibcorp.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiFxmlRegressionTest {

    @Test
    void mainToolbarMustStaySingleRowAndExposeSearchClearAndOverflowActions() throws IOException {
        String fxml = resource("/view/MainView.fxml");

        assertTrue(fxml.contains("<HBox fx:id=\"mainToolbar\""),
                "Main toolbar must use a single-row adaptive HBox");
        assertTrue(fxml.contains("styleClass=\"main-toolbar-wrap\""),
                "Main toolbar must keep the dedicated styling contract");
        assertTrue(fxml.contains("HBox.hgrow=\"ALWAYS\""),
                "Global search must absorb remaining toolbar width");
        assertTrue(fxml.contains("onAction=\"#handleClearSearch\""),
                "Global search must expose an explicit clear button");
        assertTrue(fxml.contains("<MenuButton text=\"⋮\""),
                "Infrequent actions must remain reachable from toolbar overflow");
        assertTrue(fxml.contains("onAction=\"#handleCycleApplicationTheme\""),
                "Whole-application theme switch must remain reachable from overflow");
        assertFalse(fxml.contains("<FlowPane fx:id=\"mainToolbar\""),
                "Main toolbar must not wrap into multiple rows");
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


    @Test
    void bookDetailsMustExposeMultiArtifactSelectionAndPreferredFormatActions() throws IOException {
        String fxml = resource("/view/details.fxml");

        assertTrue(fxml.contains("fx:id=\"artifactBadgesPane\""),
                "Details must expose visible format badges");
        assertTrue(fxml.contains("fx:id=\"artifactComboBox\""),
                "Details must allow selecting a concrete representation");
        assertTrue(fxml.contains("onAction=\"#onSetPreferredArtifact\""),
                "User must be able to persist the preferred representation");
        assertTrue(fxml.contains("onAction=\"#onOpenSelectedArtifact\""),
                "User must be able to open a selected representation without changing the default");
        assertTrue(fxml.contains("fx:id=\"artifactLocationLabel\""),
                "Details must show artifact source/location");
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = UiFxmlRegressionTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
