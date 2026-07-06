package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.ui.service.DialogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SettingsPresenter {

    private final DialogService dialogService;

    public void showColumnsDialog() {
        dialogService.showInfo("Налаштування", "Відображення колонок", "Функція поки що не реалізована");
    }
}