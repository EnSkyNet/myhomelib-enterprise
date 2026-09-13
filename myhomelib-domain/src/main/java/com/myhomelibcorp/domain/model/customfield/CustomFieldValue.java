package com.myhomelibcorp.domain.model.customfield;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CustomFieldValue(long definitionId, CustomFieldType type, String value) {
    public CustomFieldValue {
        if (definitionId <= 0) throw new IllegalArgumentException("definitionId must be positive");
        if (type == null) throw new IllegalArgumentException("Custom field type is required");
        value = value == null ? "" : value.trim();
        if (value.length() > 10_000) throw new IllegalArgumentException("Custom field value is too long");
        if (!value.isEmpty()) validate(type, value);
    }

    public static void validate(CustomFieldType type, String value) {
        switch (type) {
            case TEXT, ENUM -> { }
            case NUMBER -> new BigDecimal(value);
            case BOOL -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("Boolean custom field value must be true or false");
                }
            }
            case DATE -> LocalDate.parse(value);
        }
    }
}
