package com.myhomelibcorp.ui.navigation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UpdatesPeriodDropdownContractTest {

    @Test
    void updatesUseFourDropdownsAndHideTheDuplicatedNavigationList() throws Exception {
        String fxml = Files.readString(Path.of("src/main/resources/view/navigation-panel.fxml"),
                StandardCharsets.UTF_8);
        String controller = Files.readString(Path.of(
                "src/main/java/com/myhomelibcorp/ui/navigation/NavigationPanelController.java"),
                StandardCharsets.UTF_8);

        assertThat(fxml).contains(
                "fx:id=\"updatesPeriodPanel\"",
                "fx:id=\"updatesDayComboBox\"",
                "fx:id=\"updatesWeekComboBox\"",
                "fx:id=\"updatesMonthComboBox\"",
                "fx:id=\"updatesYearComboBox\"",
                "fx:id=\"navigationSearchRow\"");
        assertThat(controller).contains(
                "updatePeriodPanelVisibility(mode)",
                "navigationListView.setVisible(!updates)",
                "navigationListView.setManaged(!updates)",
                "navigationSearchRow.setVisible(!updates)",
                "navigationSearchRow.setManaged(!updates)",
                "case UPDATES -> node.label()",
                "if (mode == NavigationMode.UPDATES) {",
                "updatesDayComboBox.valueProperty().addListener(",
                "selectUpdatePeriod(updatesDayComboBox)",
                "selectUpdatePeriod(updatesWeekComboBox)",
                "selectUpdatePeriod(updatesMonthComboBox)",
                "selectUpdatePeriod(updatesYearComboBox)",
                "onNodeSelected.accept(NavigationNodeDto.of(NavigationMode.UPDATES, option.id(), option.label()))");
        assertThat(controller).contains(
                "day <= 6", "week <= 4", "offset < 60", "updates-calendar-", "updates-year-");
    }
}
