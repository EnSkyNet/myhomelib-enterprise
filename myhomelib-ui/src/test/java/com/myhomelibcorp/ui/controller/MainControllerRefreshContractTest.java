package com.myhomelibcorp.ui.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MainControllerRefreshContractTest {

    @Test
    void refreshPublishesNavigationEventWithoutSchedulingSecondDirectReload() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/myhomelibcorp/ui/controller/MainController.java"));
        int start = source.indexOf("public void handleRefresh()");
        int end = source.indexOf("@FXML", start + 1);

        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String method = source.substring(start, end);
        assertThat(method).contains("eventPublisher.publishEvent(new NavigationRefreshEvent())");
        assertThat(method).doesNotContain("navigationPanelController.refreshAll()");
    }
}
