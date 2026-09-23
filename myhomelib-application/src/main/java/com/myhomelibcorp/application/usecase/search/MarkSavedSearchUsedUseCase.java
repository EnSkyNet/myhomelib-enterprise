package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.SavedSearch;
public class MarkSavedSearchUsedUseCase {
    private final SavedSearchRepository repository;
    private final SavedSearchOperationExecutor operations;

    public MarkSavedSearchUsedUseCase(SavedSearchRepository repository) {
        this(repository, null);
    }

    public MarkSavedSearchUsedUseCase(SavedSearchRepository repository, SavedSearchOperationExecutor operations) {
        this.repository = repository;
        this.operations = operations;
    }

    public SavedSearch execute(String id) {
        if (operations == null) return executeLocked(id);
        return operations.mutate(() -> executeLocked(id));
    }

    private SavedSearch executeLocked(String id) {
        SavedSearch search = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Збережений пошук не знайдено: " + id));
        return repository.save(search.withUsage());
    }
}
