package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.usecase.navigation.LoadNavigationDataUseCase;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.NavigationViewModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryNavigationPresenter {

    private final LoadNavigationDataUseCase loadNavigationDataUseCase;
    private final ApplicationState appState;

    public void refreshAll() {
        NavigationViewModel vm = appState.getNavigation();
        vm.clear();

        loadNavigationDataUseCase.execute()
                .thenAccept(data -> UiExecutor.runOnUiThread(() -> {
                    vm.setAuthors(data.getAuthors());
                    vm.setGenres(data.getGenres());
                    vm.setSeriesNames(data.getSeriesNames());
                }))
                .exceptionally(ex -> {
                    log.error("Failed to load navigation data", ex);
                    return null;
                });
    }
}