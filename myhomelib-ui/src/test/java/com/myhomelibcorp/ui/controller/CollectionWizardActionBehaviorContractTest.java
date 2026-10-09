package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.viewmodel.CollectionWizardViewModel;
import com.myhomelibcorp.ui.service.FileChooserService;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static com.myhomelibcorp.ui.support.UiActionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionWizardActionBehaviorContractTest {
    @BeforeAll static void fxStart() throws Exception { startFx(); }

    @Test
    void onBackMovesWizardToPreviousStepAndUpdatesControls() throws Exception {
        var f = constructWithMocks(CollectionWizardController.class);
        CollectionWizardViewModel model = (CollectionWizardViewModel) getField(f.instance(), "model");
        model.setCurrentStep(1);
        setField(f.instance(), "backButton", fx(Button::new));
        setField(f.instance(), "nextButton", fx(Button::new));
        setField(f.instance(), "finishButton", fx(Button::new));
        setField(f.instance(), "errorLabel", fx(Label::new));
        setField(f.instance(), "errorLabel2", fx(Label::new));
        setField(f.instance(), "step1Indicator", fx(Label::new));
        setField(f.instance(), "step2Indicator", fx(Label::new));
        setField(f.instance(), "step3Indicator", fx(Label::new));
        StackPane content = fx(StackPane::new);
        fx(() -> { content.getChildren().addAll(new Pane(), new Pane(), new Pane()); return null; });
        setField(f.instance(), "wizardContent", content);

        invoke(f.instance(), "onBack");

        assertThat(model.getCurrentStep()).isZero();
        assertThat(fx(() -> ((Button) getField(f.instance(), "backButton")).isDisabled())).isTrue();
    }


    @Test
    void chooserActionsDelegateToFileChooserBoundaryAndUpdateWizardModel() throws Exception {
        var f = constructWithMocks(CollectionWizardController.class);
        CollectionWizardViewModel model = (CollectionWizardViewModel) getField(f.instance(), "model");
        FileChooserService chooser = f.dependency(FileChooserService.class);
        when(f.dependency(com.myhomelibcorp.ui.service.LocalizationService.class).text(anyString())).thenAnswer(i -> i.getArgument(0));
        File root = new File("/tmp/mhl-wizard-root");
        File db = new File("/tmp/mhl-wizard.db");
        File source = new File("/tmp/mhl-source.inpx");
        when(chooser.chooseDirectory(any(), anyString())).thenReturn(root);
        when(chooser.chooseFileToSave(any(), anyString(), anyString())).thenReturn(db);
        when(chooser.chooseFile(any(), anyString(), anyList())).thenReturn(source);

        invoke(f.instance(), "onSelectRootFolder");
        invoke(f.instance(), "onSelectDbPath");
        invoke(f.instance(), "onSelectSourcePath");

        assertThat(model.getRootFolder()).isEqualTo(root.toPath());
        assertThat(model.getDbFile()).isEqualTo(db.toPath());
        assertThat(model.getSourcePath()).isEqualTo(source.getAbsolutePath());
        verify(chooser).chooseDirectory(any(), anyString());
        verify(chooser).chooseFileToSave(any(), anyString(), anyString());
        verify(chooser).chooseFile(any(), anyString(), anyList());
    }

    @Test
    void assignedStageKeepsEnoughHeightForScrollableSecondStep() throws Exception {
        var f = constructWithMocks(CollectionWizardController.class);
        Stage stage = fx(Stage::new);

        fx(() -> { f.instance().setStage(stage); return null; });

        assertThat(fx(stage::getMinHeight)).isGreaterThanOrEqualTo(420.0);
        assertThat(fx(stage::isResizable)).isTrue();
    }

    @Test
    void onCancelClosesAssignedStage() throws Exception {
        var f = constructWithMocks(CollectionWizardController.class);
        Stage stage = fx(() -> { Stage s = new Stage(); s.show(); return s; });
        fx(() -> { f.instance().setStage(stage); return null; });
        invoke(f.instance(), "onCancel");
        assertThat(fx(stage::isShowing)).isFalse();
    }
}
