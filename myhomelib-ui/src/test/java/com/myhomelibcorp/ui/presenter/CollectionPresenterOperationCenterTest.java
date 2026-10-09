package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.usecase.collection.CreateCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.collection.CollectionUiCommitGuard;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.StatusBarViewModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionPresenterOperationCenterTest {
    @TempDir Path tempDir;

    @Test
    void directCollectionCreationIsVisibleInOperationCenterUntilCompletion() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(CollectionPresenter.class);
        DialogService dialogs = fixture.dependency(DialogService.class);
        FileChooserService chooser = fixture.dependency(FileChooserService.class);
        CreateCollectionUseCase create = fixture.dependency(CreateCollectionUseCase.class);
        SwitchCollectionUseCase switcher = fixture.dependency(SwitchCollectionUseCase.class);
        ApplicationState state = fixture.dependency(ApplicationState.class);
        CollectionUiCommitGuard commitGuard = fixture.dependency(CollectionUiCommitGuard.class);
        OperationCenterService operations = fixture.dependency(OperationCenterService.class);

        when(dialogs.showTextInput(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("Direct collection"));
        when(chooser.chooseFileToSave(any(), anyString(), anyString()))
                .thenReturn(tempDir.resolve("direct.db").toFile());
        Collection collection = new Collection("collection-1", "Direct collection", tempDir,
                tempDir.resolve("direct.db").toString(), 0, null, null, null, null);
        when(create.execute(any())).thenReturn(collection);
        when(switcher.execute(collection)).thenReturn(collection);
        when(state.getStatusBar()).thenReturn(new StatusBarViewModel());
        when(commitGuard.runIfStillActive(eq("collection-1"), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
        when(operations.start(anyString(), anyString(), eq(OperationKind.COLLECTION_CREATE), any(), eq(false)))
                .thenReturn("op-create-1");

        fixture.instance().showCreateCollectionDialog(null, null);

        verify(operations).start(anyString(), eq(""), eq(OperationKind.COLLECTION_CREATE), any(), eq(false));
        verify(operations).accept(anyString(), eq("collection-1"), eq(OperationKind.COLLECTION_CREATE), any(OperationProgress.class));
        verify(operations).complete(eq("op-create-1"), contains("Direct collection"));
        verify(operations, never()).fail(eq("op-create-1"), any(Throwable.class));
    }

    @Test
    void directCollectionCreationFailureIsRecordedInOperationCenter() throws Exception {
        var fixture = UiActionTestSupport.constructWithMocks(CollectionPresenter.class);
        when(fixture.dependency(DialogService.class)
                .showTextInput(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("Broken collection"));
        when(fixture.dependency(FileChooserService.class).chooseFileToSave(any(), anyString(), anyString()))
                .thenReturn(tempDir.resolve("broken.db").toFile());
        IllegalStateException failure = new IllegalStateException("create failed");
        when(fixture.dependency(CreateCollectionUseCase.class).execute(any())).thenThrow(failure);
        OperationCenterService operations = fixture.dependency(OperationCenterService.class);
        when(operations.start(anyString(), anyString(), eq(OperationKind.COLLECTION_CREATE), any(), eq(false)))
                .thenReturn("op-create-fail");

        fixture.instance().showCreateCollectionDialog(null, null);

        verify(operations).fail("op-create-fail", failure);
        verify(fixture.dependency(DialogService.class)).showError(anyString(), anyString());
    }
}
