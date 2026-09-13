package com.myhomelibcorp.application.customfield;

import com.myhomelibcorp.application.port.out.customfield.CustomFieldRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDefinition;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDeletePolicy;
import com.myhomelibcorp.domain.model.customfield.CustomFieldValue;
import com.myhomelibcorp.domain.model.customfield.CustomFieldType;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CustomFieldService {
    private final CustomFieldRepository repository;
    private final BookQueryRepository books;
    private final SearchIndexer indexer;
    private final IndexRebuilder rebuilder;

    public CustomFieldService(CustomFieldRepository repository, BookQueryRepository books,
                              SearchIndexer indexer, IndexRebuilder rebuilder) {
        this.repository = repository;
        this.books = books;
        this.indexer = indexer;
        this.rebuilder = rebuilder;
    }


    /** UI-safe view that keeps domain custom-field models behind the application boundary. */
    public record DefinitionView(Long id, String name, String type, List<String> enumOptions) {
        public DefinitionView { enumOptions = enumOptions == null ? List.of() : List.copyOf(enumOptions); }
    }

    public List<DefinitionView> definitionViews() {
        return repository.findDefinitions().stream()
                .map(d -> new DefinitionView(d.id(), d.name(), d.type().name(), d.enumOptions()))
                .toList();
    }

    public Map<Long, String> valueTexts(BookId bookId) {
        return repository.findValues(bookId).entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().value()));
    }

    public DefinitionView saveDefinition(Long id, String name, String type, List<String> enumOptions) {
        CustomFieldType parsedType = CustomFieldType.valueOf(type);
        CustomFieldDefinition saved = saveDefinition(new CustomFieldDefinition(id, name, parsedType, enumOptions));
        return new DefinitionView(saved.id(), saved.name(), saved.type().name(), saved.enumOptions());
    }

    public void deleteDefinitionCascade(long id) {
        deleteDefinition(id, CustomFieldDeletePolicy.CASCADE_VALUES);
    }

    public List<CustomFieldDefinition> definitions() { return repository.findDefinitions(); }
    public Map<Long, CustomFieldValue> values(BookId bookId) { return repository.findValues(bookId); }

    public CustomFieldDefinition saveDefinition(CustomFieldDefinition definition) {
        CustomFieldDefinition saved = repository.saveDefinition(definition);
        rebuilder.rebuildIndex();
        return saved;
    }

    public void deleteDefinition(long id, CustomFieldDeletePolicy policy) {
        repository.deleteDefinition(id, policy);
        rebuilder.rebuildIndex();
    }

    public void setValue(BookId bookId, long definitionId, String value) {
        repository.setValue(bookId, definitionId, value);
        books.findById(bookId).ifPresent(indexer::indexBook);
    }

    public void saveValues(BookId bookId, Map<Long, String> values) {
        if (bookId == null) throw new IllegalArgumentException("bookId is required");
        Map<Long, String> safe = values == null ? Map.of() : values;
        for (CustomFieldDefinition definition : repository.findDefinitions()) {
            String value = safe.get(definition.id());
            if (value == null || value.isBlank()) repository.clearValue(bookId, definition.id());
            else repository.setValue(bookId, definition.id(), value);
        }
        books.findById(bookId).ifPresent(indexer::indexBook);
    }

    public void clearValue(BookId bookId, long definitionId) {
        repository.clearValue(bookId, definitionId);
        books.findById(bookId).ifPresent(indexer::indexBook);
    }
}
