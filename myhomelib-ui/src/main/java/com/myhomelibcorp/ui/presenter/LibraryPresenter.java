package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryPresenter {

    private final DialogService dialogService;
    private final FileChooserService fileChooserService;
    private final StatusBarPresenter statusBarPresenter;

    /**
     * Відкриває існуючу колекцію.
     */
    public void openCollection(Stage owner) {
        // TODO: реалізувати вибір файлу бази даних
        dialogService.showInfo("Інформація", "Відкриття колекції", "Функція поки що не реалізована");
    }

    /**
     * Створює нову колекцію.
     */
    public void createNewCollection(Stage owner) {
        // TODO: реалізувати створення нової колекції
        dialogService.showInfo("Інформація", "Створення колекції", "Функція поки що не реалізована");
    }

    /**
     * Експортує бібліотеку.
     */
    public void exportLibrary(Stage owner) {
        // TODO: реалізувати експорт
        dialogService.showInfo("Інформація", "Експорт", "Функція поки що не реалізована");
    }
}