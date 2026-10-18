package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.ui.service.UiText;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OperationKindLabelTest {
    @Test void organizerHasItsOwnStableLocalizedCategory() {
        assertThat(OperationCenterController.kindText(OperationKind.LIBRARY_ORGANIZATION))
                .isEqualTo(UiText.text("ui.organizer.title"));
    }
}
