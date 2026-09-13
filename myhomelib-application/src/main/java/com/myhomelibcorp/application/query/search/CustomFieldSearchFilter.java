package com.myhomelibcorp.application.query.search;

import com.myhomelibcorp.domain.model.customfield.CustomFieldType;

public record CustomFieldSearchFilter(long definitionId, CustomFieldType type,
                                      CustomFieldSearchOperator operator, String value, String secondValue) {
    public CustomFieldSearchFilter {
        if (definitionId <= 0) throw new IllegalArgumentException("definitionId must be positive");
        if (type == null || operator == null) throw new IllegalArgumentException("Custom field filter type/operator is required");
        value = value == null ? "" : value.trim();
        secondValue = secondValue == null ? "" : secondValue.trim();
    }
}
