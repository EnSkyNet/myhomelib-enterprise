package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
public class LoadSmartCollectionDefinitionUseCase {
    private final SavedSearchRepository repository;
    private final SavedSearchOperationExecutor operations;

    public LoadSmartCollectionDefinitionUseCase(SavedSearchRepository repository) {
        this(repository, null);
    }

    public LoadSmartCollectionDefinitionUseCase(SavedSearchRepository repository, SavedSearchOperationExecutor operations) {
        this.repository = repository;
        this.operations = operations;
    }

    public SmartCollectionDefinition execute(String id) {
        if (operations == null) return executeLocked(id);
        return operations.query(() -> executeLocked(id));
    }

    private SmartCollectionDefinition executeLocked(String id) {
        var saved = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Smart collection not found: " + id));
        if (!saved.isSmartCollection()) {
            throw new IllegalArgumentException("Saved search is not a smart collection: " + id);
        }
        var spec = saved.getSmartCollection();
        return new SmartCollectionDefinition(
                saved.getId(), saved.getName(), saved.isPinned(), spec.mode().name(), spec.sort().name(),
                spec.direction().name(), spec.maxResults(), spec.rules().stream()
                .map(rule -> new SmartCollectionDefinition.Rule(
                        rule.field().name(), rule.operator().name(), rule.value(), rule.secondValue()))
                .toList());
    }
}
