package com.myhomelibcorp.application.port.out.customfield;

import com.myhomelibcorp.domain.model.customfield.CustomFieldDefinition;
import com.myhomelibcorp.domain.model.customfield.CustomFieldDeletePolicy;
import com.myhomelibcorp.domain.model.customfield.CustomFieldValue;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CustomFieldRepository {
    List<CustomFieldDefinition> findDefinitions();
    Optional<CustomFieldDefinition> findDefinition(long id);
    CustomFieldDefinition saveDefinition(CustomFieldDefinition definition);
    void deleteDefinition(long id, CustomFieldDeletePolicy policy);
    Map<Long, CustomFieldValue> findValues(BookId bookId);
    void setValue(BookId bookId, long definitionId, String value);
    void clearValue(BookId bookId, long definitionId);
}
