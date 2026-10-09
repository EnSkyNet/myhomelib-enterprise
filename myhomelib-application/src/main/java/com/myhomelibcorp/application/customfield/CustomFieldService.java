package com.myhomelibcorp.application.customfield;

import static com.myhomelibcorp.application.operation.LibraryOperationLeaseSupport.withSharedLease;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.customfield.CustomFieldRepository;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDefinition;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDeletePolicy;
import com.myhomelibcorp.domain.model.customfield.CustomFieldValue;
import com.myhomelibcorp.domain.model.customfield.CustomFieldType;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class CustomFieldService {
    private final CustomFieldRepository repository;
    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final LibraryOperationCoordinator operationCoordinator;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final TransactionTemplate transactionTemplate;

    public CustomFieldService(CustomFieldRepository repository,
                              SearchIndexSynchronizer searchIndexSynchronizer,
                              LibraryOperationCoordinator operationCoordinator,
                              CollectionLifecyclePort collectionLifecyclePort,
                              @Qualifier("collectionTransactionTemplate") TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.searchIndexSynchronizer = searchIndexSynchronizer;
        this.operationCoordinator = operationCoordinator;
        this.collectionLifecyclePort = collectionLifecyclePort;
        this.transactionTemplate = transactionTemplate;
    }


    /** UI-safe view that keeps domain custom-field models behind the application boundary. */
    public record DefinitionView(Long id, String name, String type, List<String> enumOptions) {
        public DefinitionView { enumOptions = enumOptions == null ? List.of() : List.copyOf(enumOptions); }
    }

    public List<DefinitionView> definitionViews() {
        return withQueryLease(() -> repository.findDefinitions().stream()
                .map(d -> new DefinitionView(d.id(), d.name(), d.type().name(), d.enumOptions()))
                .toList());
    }

    public Map<Long, String> valueTexts(BookId bookId) {
        if (bookId == null) return Map.of();
        return withQueryLease(() -> repository.findValues(bookId).entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().value())));
    }

    public DefinitionView saveDefinition(Long id, String name, String type, List<String> enumOptions) {
        return saveDefinition(id, name, type, enumOptions, null);
    }

    public DefinitionView saveDefinition(Long id, String name, String type, List<String> enumOptions,
                                         String expectedCollectionId) {
        CustomFieldType parsedType = CustomFieldType.valueOf(type);
        CustomFieldDefinition saved = saveDefinition(
                new CustomFieldDefinition(id, name, parsedType, enumOptions), expectedCollectionId);
        return new DefinitionView(saved.id(), saved.name(), saved.type().name(), saved.enumOptions());
    }

    public void deleteDefinitionCascade(long id) {
        deleteDefinitionCascade(id, null);
    }

    public void deleteDefinitionCascade(long id, String expectedCollectionId) {
        deleteDefinition(id, CustomFieldDeletePolicy.CASCADE_VALUES, expectedCollectionId);
    }

    public List<CustomFieldDefinition> definitions() {
        return withQueryLease(repository::findDefinitions);
    }

    public Map<Long, CustomFieldValue> values(BookId bookId) {
        if (bookId == null) return Map.of();
        return withQueryLease(() -> repository.findValues(bookId));
    }

    public CustomFieldDefinition saveDefinition(CustomFieldDefinition definition) {
        return saveDefinition(definition, null);
    }

    public CustomFieldDefinition saveDefinition(CustomFieldDefinition definition, String expectedCollectionId) {
        return withEditLease(() -> {
            assertExpectedCollection(expectedCollectionId);
            CustomFieldDefinition saved = inTransaction(() -> repository.saveDefinition(definition));
            requireFullIndexRefresh("Custom field definition was saved, but search-index rebuild failed");
            return saved;
        });
    }

    public void deleteDefinition(long id, CustomFieldDeletePolicy policy) {
        deleteDefinition(id, policy, null);
    }

    public void deleteDefinition(long id, CustomFieldDeletePolicy policy, String expectedCollectionId) {
        withEditLease(() -> {
            assertExpectedCollection(expectedCollectionId);
            inTransaction(() -> {
                repository.deleteDefinition(id, policy);
                return null;
            });
            requireFullIndexRefresh("Custom field definition was deleted, but search-index rebuild failed");
            return null;
        });
    }

    public void setValue(BookId bookId, long definitionId, String value) {
        setValue(bookId, definitionId, value, null);
    }

    public void setValue(BookId bookId, long definitionId, String value, String expectedCollectionId) {
        if (bookId == null) throw new IllegalArgumentException("bookId is required");
        withEditLease(() -> {
            assertExpectedCollection(expectedCollectionId);
            inTransaction(() -> {
                repository.setValue(bookId, definitionId, value);
                return null;
            });
            requireBookIndexRefresh(bookId);
            return null;
        });
    }

    public void saveValues(BookId bookId, Map<Long, String> values) {
        saveValues(bookId, values, null);
    }

    public void saveValues(BookId bookId, Map<Long, String> values, String expectedCollectionId) {
        if (bookId == null) throw new IllegalArgumentException("bookId is required");
        withEditLease(() -> {
            assertExpectedCollection(expectedCollectionId);
            inTransaction(() -> {
                Map<Long, String> safe = values == null ? Map.of() : values;
                for (CustomFieldDefinition definition : repository.findDefinitions()) {
                    String value = safe.get(definition.id());
                    if (value == null || value.isBlank()) repository.clearValue(bookId, definition.id());
                    else repository.setValue(bookId, definition.id(), value);
                }
                return null;
            });
            requireBookIndexRefresh(bookId);
            return null;
        });
    }

    public void clearValue(BookId bookId, long definitionId) {
        clearValue(bookId, definitionId, null);
    }

    public void clearValue(BookId bookId, long definitionId, String expectedCollectionId) {
        if (bookId == null) return;
        withEditLease(() -> {
            assertExpectedCollection(expectedCollectionId);
            inTransaction(() -> {
                repository.clearValue(bookId, definitionId);
                return null;
            });
            requireBookIndexRefresh(bookId);
            return null;
        });
    }

    private void assertExpectedCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) return;
        Collection active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null
                || !expectedCollectionId.trim().equals(active.getId().trim())) {
            throw new IllegalStateException("Активна колекція змінилася до операції з користувацькими полями");
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        T result = transactionTemplate.execute(status -> action.get());
        if (result == null) {
            // Void-style mutations intentionally return null; Spring may also return null for a callback result.
            return null;
        }
        return result;
    }

    private void requireBookIndexRefresh(BookId bookId) {
        if (!searchIndexSynchronizer.synchronizeSafelyNow(List.of(bookId))) {
            throw new IllegalStateException("Custom field values were saved, but search-index synchronization failed");
        }
    }

    private void requireFullIndexRefresh(String message) {
        if (!searchIndexSynchronizer.rebuildSafelyNow()) {
            throw new IllegalStateException(message);
        }
    }

    private <T> T withQueryLease(Supplier<T> action) {
        return withSharedLease(operationCoordinator, LibraryOperationType.QUERY, action);
    }

    private <T> T withEditLease(Supplier<T> action) {
        return withSharedLease(operationCoordinator, LibraryOperationType.EDIT, action);
    }
}
