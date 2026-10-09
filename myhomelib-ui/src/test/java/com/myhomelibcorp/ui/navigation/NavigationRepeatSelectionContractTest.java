package com.myhomelibcorp.ui.navigation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationRepeatSelectionContractTest {

    @Test
    void alreadySelectedNavigationNodeIsRedispatchedOnRealCellClick() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/myhomelibcorp/ui/navigation/NavigationPanelController.java"),
                StandardCharsets.UTF_8);

        assertThat(source).contains(
                "selectionBeforePointerPress = navigationListView.getSelectionModel().getSelectedItem()",
                "selected.equals(selectionBeforePointerPress)",
                "isNonEmptyListCell(event.getTarget())",
                "onNodeSelected.accept(selected)");
    }
}
