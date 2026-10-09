package com.myhomelibcorp.ui.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationCenterKoboActionContractTest {
    private static String source() throws Exception {
        Path path = Path.of("src/main/java/com/myhomelibcorp/ui/integration/IntegrationCenterDialog.java");
        String source = Files.readString(path);
        assertThat(source).contains("class IntegrationCenterDialog");
        return source;
    }

    @Test
    void copyActionCopiesOnlyTheOneTimeCredentialText() throws Exception {
        assertThat(source())
                .contains("copy.setOnAction")
                .contains("copyText(credential.getText(), UiText.text(\"ui.integration.kobo.copied\"))");
    }

    @Test
    void syncNowActionDelegatesToKoboBackendAndRefreshesStatus() throws Exception {
        assertThat(source())
                .contains("syncNow.setOnAction")
                .contains("backend.synchronizeKoboNow()")
                .contains("refresh.run()");
    }
}
