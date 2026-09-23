package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SaveSearchUseCase {

    private final SavedSearchRepository savedSearchRepository;
    private final SavedSearchOperationExecutor operations;

    public SaveSearchUseCase(SavedSearchRepository savedSearchRepository) {
        this(savedSearchRepository, null);
    }

    public SaveSearchUseCase(SavedSearchRepository savedSearchRepository, SavedSearchOperationExecutor operations) {
        this.savedSearchRepository = savedSearchRepository;
        this.operations = operations;
    }

    public SavedSearch execute(String name, String query, String filters) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Назва пошуку не може бути порожньою");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Запит не може бути порожнім");
        }

        if (operations == null) return executeLocked(name, query, filters);
        return operations.mutate(() -> executeLocked(name, query, filters));
    }

    private SavedSearch executeLocked(String name, String query, String filters) {
        // Перевіряємо, чи вже існує пошук з такою назвою
        savedSearchRepository.findByName(name).ifPresent(existing -> {
            savedSearchRepository.deleteById(existing.getId());
            log.info("Видалено старий пошук з назвою: {}", name);
        });

        SavedSearch search = new SavedSearch(name, query, filters);
        SavedSearch saved = savedSearchRepository.save(search);
        log.info("Збережено пошук: {} (id: {})", name, saved.getId());
        return saved;
    }
}