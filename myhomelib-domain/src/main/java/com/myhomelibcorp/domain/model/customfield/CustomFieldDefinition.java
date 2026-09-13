package com.myhomelibcorp.domain.model.customfield;

import java.util.List;

public record CustomFieldDefinition(Long id, String name, CustomFieldType type, List<String> enumOptions) {
    public CustomFieldDefinition {
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("Custom field name must contain 1..80 characters");
        if (type == null) throw new IllegalArgumentException("Custom field type is required");
        enumOptions = enumOptions == null ? List.of() : enumOptions.stream()
                .map(v -> v == null ? "" : v.trim()).filter(v -> !v.isEmpty()).distinct().toList();
        if (type == CustomFieldType.ENUM && enumOptions.isEmpty()) {
            throw new IllegalArgumentException("ENUM custom field requires at least one option");
        }
        if (type != CustomFieldType.ENUM && !enumOptions.isEmpty()) {
            throw new IllegalArgumentException("Enum options are only valid for ENUM fields");
        }
        if (enumOptions.size() > 200) throw new IllegalArgumentException("ENUM custom field supports at most 200 options");
    }
}
