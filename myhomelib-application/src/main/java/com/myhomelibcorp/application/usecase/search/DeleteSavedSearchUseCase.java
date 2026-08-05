package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class DeleteSavedSearchUseCase {

    private final SavedSearchRepository savedSearchRepository;

    public void execute(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ID пошуку не може бути порожнім");
        }
        savedSearchRepository.deleteById(id);
        log.info("Видалено пошук з id: {}", id);
    }

    public void executeByName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Назва пошуку не може бути порожньою");
        }
        savedSearchRepository.deleteByName(name);
        log.info("Видалено пошук з назвою: {}", name);
    }
}