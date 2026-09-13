package com.myhomelibcorp.application.usecase.search;

import java.util.List;

/** UI-safe smart-collection definition that does not expose domain model types. */
public record SmartCollectionDefinition(
        String id,
        String name,
        boolean pinned,
        String mode,
        String sort,
        String direction,
        int maxResults,
        List<Rule> rules
) {
    public SmartCollectionDefinition {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public record Rule(String field, String operator, String value, String secondValue) { }
}
