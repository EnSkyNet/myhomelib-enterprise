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
                .contains("exportCancelFlag.set(true)")
                .contains("перед фізичним від’єднанням скористайтеся безпечним вилученням ОС");
    }
}
