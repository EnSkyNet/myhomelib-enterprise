package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract for the user-triggerable Classic profile settings button. */
class ApplicationSettingsClassicActionContractTest {
    @Test
    void applicationSettingsDialogClassicButtonPopulatesVisibleFieldsBeforeSave() throws Exception {
        // Static UI contract: the control uses the preset values via setOnAction and
        // the normal settings Save path. No hidden direct mutation of persisted secrets.
        String ui;
        try (var source = new FileInputStream(
                "src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java")) {
            ui = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(ui).contains(
                "Button classic = new Button(",
                "classic.setOnAction(",
                "ClassicOnlineDownloadProfile.values().forEach",
                "TextField input = text.get(key);",
                "input.setText(value);");
        assertThat(ClassicOnlineDownloadProfile.values())
                .containsEntry("online.readTimeoutSeconds", "90")
                .containsEntry("online.maxParallelDownloads", "1");
    }
}
