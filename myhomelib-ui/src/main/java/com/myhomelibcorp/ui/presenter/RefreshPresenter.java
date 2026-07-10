package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.NavigationViewModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RefreshPresenter {

    private final ApplicationState appState;
    private final BookLoaderService bookLoaderService;
    private final LibraryNavigationPresenter navigationPresenter;

    public void refreshAll() {
        log.info("Оновлення всієї бібліотеки...");
        appState.getStatusBar().setStatusText("Оновлення...");

        // Очистити кеші ViewModel
        appState.getDashboard().clear();
        appState.getSearch().clearResults();
        appState.getNavigation().clear();
        appState.getBookTable().clear();
        appState.getBookDetails().clear();

        // Перезавантажити навігацію
        navigationPresenter.refreshAll();

        // Завантажити всі книги
        bookLoaderService.loadAllBooks();

        // Оновити статистику (викликається окремо через події)
        appState.getStatusBar().setStatusText("Оновлення завершено");
    }
}