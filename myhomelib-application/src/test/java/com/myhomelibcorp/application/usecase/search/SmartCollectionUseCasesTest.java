package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SmartCollectionUseCasesTest {
    @Test
    void uiSafeDefinitionRoundTripsAndBuildsSearchRequest() {
        MemoryRepository repository = new MemoryRepository();
        SavedSearch original = repository.save(SavedSearch.smartCollection("Unread FB2",
                new SmartCollectionSpec(SmartCollectionMode.AND,
                        List.of(new SmartCollectionRule(SmartCollectionField.FORMAT, SmartCollectionOperator.EQUALS, "fb2", null)),
                        SmartCollectionSort.TITLE, SmartCollectionSortDirection.ASC, 5000), true));

        SmartCollectionDefinition definition = new LoadSmartCollectionDefinitionUseCase(repository).execute(original.getId());
        assertThat(definition.name()).isEqualTo("Unread FB2");
        assertThat(definition.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.field()).isEqualTo("FORMAT");
            assertThat(rule.operator()).isEqualTo("EQUALS");
        });

        var request = new BuildSmartCollectionSearchRequestUseCase(repository).execute(original.getId(), 500, 0);
        assertThat(request.limit()).isEqualTo(500);
        assertThat(request.smartCollectionSpec().maxResults()).isEqualTo(5000);

        SmartCollectionDefinition edited = new SmartCollectionDefinition(original.getId(), definition.name(), false,
                "OR", "YEAR", "DESC", 2500,
                List.of(new SmartCollectionDefinition.Rule("YEAR", "AT_LEAST", "2020", null)));
        SmartCollectionDefinition saved = new SaveSmartCollectionUseCase(repository).execute(edited);
        assertThat(saved.id()).isEqualTo(original.getId());
        assertThat(saved.pinned()).isFalse();
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findById(original.getId()).orElseThrow().getSmartCollection().mode()).isEqualTo(SmartCollectionMode.OR);
    }

    private static final class MemoryRepository implements SavedSearchRepository {
        private final List<SavedSearch> searches = new ArrayList<>();
        @Override public List<SavedSearch> findAll() { return List.copyOf(searches); }
        @Override public Optional<SavedSearch> findById(String id) { return searches.stream().filter(s -> s.getId().equals(id)).findFirst(); }
        @Override public Optional<SavedSearch> findByName(String name) { return searches.stream().filter(s -> s.getName().equals(name)).findFirst(); }
        @Override public SavedSearch save(SavedSearch search) {
            searches.removeIf(existing -> existing.getId().equals(search.getId()));
            searches.add(search);
            return search;
        }
        @Override public void deleteById(String id) { searches.removeIf(search -> search.getId().equals(id)); }
    }
}
