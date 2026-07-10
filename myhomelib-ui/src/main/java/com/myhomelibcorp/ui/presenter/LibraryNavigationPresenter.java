package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.navigation.NavigationService;
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

    private final NavigationService navigationService;
    private final ApplicationState appState;

    public void refreshAll() {
        NavigationViewModel vm = appState.getNavigation();
        vm.clear();

        navigationService.getAllAuthors().thenAccept(authors -> UiExecutor.runOnUiThread(() ->
                vm.setAuthors(authors)
        )).exceptionally(ex -> {
            log.error("Failed to load authors", ex);
            return null;
        });

        navigationService.getAllSeriesNames().thenAccept(series -> UiExecutor.runOnUiThread(() ->
                vm.setSeriesNames(series)
        )).exceptionally(ex -> {
            log.error("Failed to load series", ex);
            return null;
        });

        navigationService.getAllGenres().thenAccept(genres -> UiExecutor.runOnUiThread(() ->
                vm.setGenres(genres)
        )).exceptionally(ex -> {
            log.error("Failed to load genres", ex);
            return null;
        });
    }
}