package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SmartCollectionActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void onAddRuleAddsARowAndProgrammaticRemoveDeletesOnlyWhenMoreThanOneRuleExists() throws Exception {
        var f = fixture();
        VBox rules = (VBox) UiActionTestSupport.getField(f.instance(), "rulesBox");
        assertThat(UiActionTestSupport.fx(() -> rules.getChildren().size())).isEqualTo(1);
        UiActionTestSupport.invoke(f.instance(), "onAddRule");
        assertThat(UiActionTestSupport.fx(() -> rules.getChildren().size())).isEqualTo(2);
        Object second = UiActionTestSupport.fx(() -> rules.getChildren().get(1));
        Button remove = (Button) UiActionTestSupport.getField(second, "remove");
        UiActionTestSupport.fx(() -> { remove.fire(); return null; });
        assertThat(UiActionTestSupport.fx(() -> rules.getChildren().size())).isEqualTo(1);
    }

    @Test
    void onSaveRejectsBlankNameBeforePersistence() throws Exception {
        var f = fixture();
        UiActionTestSupport.invoke(f.instance(), "onSave");
        verify(f.dependency(DialogService.class)).showWarning(anyString(), anyString());
        verifyNoInteractions(f.dependency(com.myhomelibcorp.application.usecase.search.SaveSmartCollectionUseCase.class));
    }

    private static UiActionTestSupport.Fixture<SmartCollectionDialogController> fixture() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(SmartCollectionDialogController.class);
        when(f.dependency(LocalizationService.class).text(anyString())).thenAnswer(i -> i.getArgument(0));
        when(f.dependency(LocalizationService.class).format(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        UiActionTestSupport.setField(f.instance(), "nameField", UiActionTestSupport.fx(TextField::new));
        UiActionTestSupport.setField(f.instance(), "pinnedCheck", UiActionTestSupport.fx(CheckBox::new));
        UiActionTestSupport.setField(f.instance(), "modeCombo", UiActionTestSupport.fx(ComboBox::new));
        UiActionTestSupport.setField(f.instance(), "sortCombo", UiActionTestSupport.fx(ComboBox::new));
        UiActionTestSupport.setField(f.instance(), "directionCombo", UiActionTestSupport.fx(ComboBox::new));
        UiActionTestSupport.setField(f.instance(), "limitField", UiActionTestSupport.fx(TextField::new));
        UiActionTestSupport.setField(f.instance(), "rulesBox", UiActionTestSupport.fx(VBox::new));
        UiActionTestSupport.setField(f.instance(), "addRuleButton", UiActionTestSupport.fx(Button::new));
        UiActionTestSupport.fx(() -> { f.instance().initialize(); return null; });
        return f;
    }
}
