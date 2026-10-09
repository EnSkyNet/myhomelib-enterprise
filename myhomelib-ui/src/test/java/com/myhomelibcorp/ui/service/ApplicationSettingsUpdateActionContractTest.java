package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationSettingsUpdateActionContractTest {
    private static String source() throws Exception {
        Path path = Path.of("src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java");
        String source = Files.readString(path);
        assertThat(source).contains("class ApplicationSettingsDialog");
        return source;
    }

    @Test
    void updateActionsHaveStableIdsAndDelegateToUpdateService() throws Exception {
        assertThat(source())
                .contains("checkNow.setId(\"application-update-check\")")
                .contains("stage.setId(\"application-update-stage\")")
                .contains("restart.setId(\"application-update-restart\")")
                .contains("applicationUpdateUiService.checkNow(selected)")
                .contains("applicationUpdateUiService.stage(candidate)")
                .contains("applicationUpdateUiService.restartAndApply()");
    }
}
