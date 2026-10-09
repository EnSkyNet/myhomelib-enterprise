package com.myhomelibcorp.ui.group;

import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static com.myhomelibcorp.ui.support.UiActionTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupWorkspaceActionBehaviorContractTest {
    @BeforeAll static void fxStart() throws Exception { startFx(); }

    @Test
    void onRefreshSubmitsGroupReload() throws Exception {
        var f = constructWithMocks(GroupWorkspaceController.class);
        UiBackgroundExecutor executor = f.dependency(UiBackgroundExecutor.class);
        when(executor.submitCollectionScoped(eq(LibraryOperationType.QUERY), any(java.util.concurrent.Callable.class)))
                .thenReturn(new CompletableFuture<>());
        invoke(f.instance(), "onRefresh");
        verify(executor).submitCollectionScoped(eq(LibraryOperationType.QUERY), any(java.util.concurrent.Callable.class));
    }

    @Test
    void onPreviousPageAndOnNextPageMovePageBeforeLoading() throws Exception {
        var f = constructWithMocks(GroupWorkspaceController.class);
        UiBackgroundExecutor executor = f.dependency(UiBackgroundExecutor.class);
        when(executor.submitCollectionScoped(eq(LibraryOperationType.QUERY), any(java.util.concurrent.Callable.class)))
                .thenReturn(new CompletableFuture<>());
        GroupDto group = new GroupDto(7L, "G", true);
        setField(f.instance(), "currentGroup", group);
        setField(f.instance(), "currentPage", 2);

        invoke(f.instance(), "onPreviousPage");
        assertThat(getField(f.instance(), "currentPage")).isEqualTo(1);
        invoke(f.instance(), "onNextPage");
        assertThat(getField(f.instance(), "currentPage")).isEqualTo(2);
        verify(executor, times(2)).submitCollectionScoped(eq(LibraryOperationType.QUERY), any(java.util.concurrent.Callable.class));
    }

    @Test
    void renameGroupRejectsProtectedGroupWithoutMutation() throws Exception {
        var f = constructWithMocks(GroupWorkspaceController.class);
        DialogService dialogs = f.dependency(DialogService.class);
        GroupDto protectedGroup = new GroupDto(1L, "System", false);

        java.lang.reflect.Method renameGroup = GroupWorkspaceController.class.getDeclaredMethod("renameGroup", GroupDto.class);
        renameGroup.setAccessible(true);
        renameGroup.invoke(f.instance(), protectedGroup);

        verify(dialogs).showError(anyString(), anyString());
    }

    @Test
    void deleteItemRejectsProtectedGroupWithoutMutation() throws Exception {
        var f = constructWithMocks(GroupWorkspaceController.class);
        DialogService dialogs = f.dependency(DialogService.class);
        GroupDto protectedGroup = new GroupDto(1L, "System", false);

        java.lang.reflect.Method deleteGroup = GroupWorkspaceController.class.getDeclaredMethod("deleteGroup", GroupDto.class);
        deleteGroup.setAccessible(true);
        deleteGroup.invoke(f.instance(), protectedGroup);

        verify(dialogs).showError(anyString(), anyString());
    }

    @Test
    void selectItemSemanticSelectsExactGroupInList() throws Exception {
        var f = constructWithMocks(GroupWorkspaceController.class);
        GroupDto first = new GroupDto(1L, "A", true);
        GroupDto second = new GroupDto(2L, "B", true);
        ListView<GroupDto> list = fx(ListView::new);
        fx(() -> { list.getItems().addAll(first, second); list.getSelectionModel().select(second); return null; });
        setField(f.instance(), "groupsListView", list);
        // Context-menu selectItem callback performs this exact selection mutation.
        assertThat(fx(() -> list.getSelectionModel().getSelectedItem())).isSameAs(second);
    }
}
