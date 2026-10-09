package com.myhomelibcorp.ui.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationCenterReadwiseActionContractTest {
    private static String source() throws Exception {
        Path path = Path.of("src/main/java/com/myhomelibcorp/ui/integration/IntegrationCenterDialog.java");
        String source = Files.readString(path);
        assertThat(source).contains("class IntegrationCenterDialog");
        return source;
    }

    @Test
    void readwiseActionsHaveStableIdsAndDelegateWithoutRenderingStoredToken() throws Exception {
        assertThat(source())
                .contains("save.setId(\"readwise-save-token\")")
                .contains("test.setId(\"readwise-test\")")
                .contains("export.setId(\"readwise-export-all\")")
                .contains("clear.setId(\"readwise-clear-token\")")
                .contains("backend.saveReadwiseToken(token.getText())")
                .contains("backend.testReadwise()")
                .contains("backend.exportAnnotationsToReadwise()")
                .contains("backend.clearReadwiseToken()")
                .doesNotContain("token.setText(backend");
    }
}
