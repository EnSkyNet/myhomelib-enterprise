package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeleteSavedSearchUseCase {

    private final SavedSearchRepository savedSearchRepository;
    private final SavedSearchOperationExecutor operations;

    public DeleteSavedSearchUseCase(SavedSearchRepository savedSearchRepository) {
        this(savedSearchRepository, null);
    }

    public DeleteSavedSearchUseCase(SavedSearchRepository savedSearchRepository, SavedSearchOperationExecutor operations) {
        this.savedSearchRepository = savedSearchRepository;
        this.operations = operations;
    }

    public void execute(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ID пошуку не може бути порожнім");
        }
        Runnable action = () -> {
            savedSearchRepository.deleteById(id);
            log.info("Видалено пошук з id: {}", id);
        };
        if (operations == null) action.run();
        else operations.mutate(action);
    }

}