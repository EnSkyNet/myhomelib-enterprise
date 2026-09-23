package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.SavedSearch;
public class SetSavedSearchPinnedUseCase {
    private final SavedSearchRepository repository;
    private final SavedSearchOperationExecutor operations;

    public SetSavedSearchPinnedUseCase(SavedSearchRepository repository) {
        this(repository, null);
    }

    public SetSavedSearchPinnedUseCase(SavedSearchRepository repository, SavedSearchOperationExecutor operations) {
        this.repository = repository;
        this.operations = operations;
    }

    public SavedSearch execute(String id, boolean pinned) {
        if (operations == null) return executeLocked(id, pinned);
        return operations.mutate(() -> executeLocked(id, pinned));
    }

    private SavedSearch executeLocked(String id, boolean pinned) {
        SavedSearch search = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Збережений пошук не знайдено: " + id));
        return repository.save(search.withPinned(pinned));
    }
}
