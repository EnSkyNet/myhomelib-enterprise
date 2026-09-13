package com.myhomelibcorp.domain.model.search;

import java.util.List;

public record SmartCollectionSpec(
        SmartCollectionMode mode,
        List<SmartCollectionRule> rules,
        SmartCollectionSort sort,
        SmartCollectionSortDirection direction,
        int maxResults
) {
    public static final int MAX_RULES = 20;
    public static final int MAX_RESULTS = 100_000;

    public SmartCollectionSpec {
        mode = mode == null ? SmartCollectionMode.AND : mode;
        rules = rules == null ? List.of() : List.copyOf(rules);
        if (rules.isEmpty()) throw new IllegalArgumentException("Smart collection requires at least one rule");
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("Smart collection supports at most " + MAX_RULES + " rules");
        if (rules.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Smart collection rule cannot be null");
        sort = sort == null ? SmartCollectionSort.TITLE : sort;
        direction = direction == null ? SmartCollectionSortDirection.ASC : direction;
        if (maxResults <= 0 || maxResults > MAX_RESULTS) {
            throw new IllegalArgumentException("maxResults must be 1.." + MAX_RESULTS);
        }
    }

    public static SmartCollectionSpec of(SmartCollectionMode mode, List<SmartCollectionRule> rules) {
        return new SmartCollectionSpec(mode, rules, SmartCollectionSort.TITLE,
                SmartCollectionSortDirection.ASC, 10_000);
    }
}
