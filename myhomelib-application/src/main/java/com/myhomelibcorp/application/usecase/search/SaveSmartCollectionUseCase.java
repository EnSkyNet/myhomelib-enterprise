package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import com.myhomelibcorp.domain.model.search.SmartCollectionSpec;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SaveSmartCollectionUseCase {
    private final SavedSearchRepository repository;

    public SmartCollectionDefinition execute(SmartCollectionDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("Smart collection definition is required");
        var rules = definition.rules().stream()
                .map(rule -> new com.myhomelibcorp.domain.model.search.SmartCollectionRule(
                        com.myhomelibcorp.domain.model.search.SmartCollectionField.valueOf(rule.field()),
                        com.myhomelibcorp.domain.model.search.SmartCollectionOperator.valueOf(rule.operator()),
                        rule.value(), rule.secondValue()))
                .toList();
        var spec = new SmartCollectionSpec(
                com.myhomelibcorp.domain.model.search.SmartCollectionMode.valueOf(definition.mode()),
                rules,
                com.myhomelibcorp.domain.model.search.SmartCollectionSort.valueOf(definition.sort()),
                com.myhomelibcorp.domain.model.search.SmartCollectionSortDirection.valueOf(definition.direction()),
                definition.maxResults());

        SavedSearch saved;
        if (definition.id() != null && !definition.id().isBlank()) {
            saved = repository.findById(definition.id())
                    .filter(SavedSearch::isSmartCollection)
                    .map(existing -> repository.save(existing.withSmartCollection(spec).withPinned(definition.pinned())))
                    .orElseThrow(() -> new IllegalArgumentException("Smart collection not found: " + definition.id()));
        } else {
            saved = execute(definition.name(), spec, definition.pinned());
        }
        var savedSpec = saved.getSmartCollection();
        return new SmartCollectionDefinition(saved.getId(), saved.getName(), saved.isPinned(),
                savedSpec.mode().name(), savedSpec.sort().name(), savedSpec.direction().name(), savedSpec.maxResults(),
                savedSpec.rules().stream().map(rule -> new SmartCollectionDefinition.Rule(
                        rule.field().name(), rule.operator().name(), rule.value(), rule.secondValue())).toList());
    }

    public SavedSearch execute(String name, SmartCollectionSpec spec, boolean pinned) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Назва smart-колекції не може бути порожньою");
        if (spec == null) throw new IllegalArgumentException("Правила smart-колекції обов'язкові");
        return repository.findByName(name.trim())
                .map(existing -> {
                    if (existing.isSmartCollection()) {
                        return repository.save(existing.withSmartCollection(spec).withPinned(pinned));
                    }
                    repository.deleteById(existing.getId());
                    return repository.save(SavedSearch.smartCollection(name, spec, pinned));
                })
                .orElseGet(() -> repository.save(SavedSearch.smartCollection(name, spec, pinned)));
    }
}
