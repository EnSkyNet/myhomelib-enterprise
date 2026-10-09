package com.myhomelibcorp.ui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExportControllerSendToDeviceContractTest {

    @Test
    void sendToDeviceKeepsBatchProgressCollisionPromptAndEjectSafeCompletion() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/controller/ExportController.java"),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains(".completionPolicy(ExportRequest.CompletionPolicy.EJECT_SAFE)")
                .contains("exportToDeviceUseCase.execute(request, exportCancelFlag, progress ->")
                .contains("this::resolveCollision")
                .contains("requestExportCancellation()")
                .contains("answer.get(250, TimeUnit.MILLISECONDS)")
                .contains("exportCancelFlag.get()")
                .doesNotContain("return answer.get();")
                .contains("UiText.text(\"ui.java.export_controller.a7c94edeb8\")");
    }
}
