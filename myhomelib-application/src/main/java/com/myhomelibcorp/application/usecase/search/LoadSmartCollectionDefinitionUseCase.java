package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LoadSmartCollectionDefinitionUseCase {
    private final SavedSearchRepository repository;

    public SmartCollectionDefinition execute(String id) {
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
