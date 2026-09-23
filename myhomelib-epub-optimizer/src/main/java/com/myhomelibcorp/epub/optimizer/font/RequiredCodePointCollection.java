package com.myhomelibcorp.epub.optimizer.font;

import java.util.List;
import java.util.Set;

/** Unicode repertoire required by reachable publication text. Incomplete collections must never drive font subsetting. */
public record RequiredCodePointCollection(Set<Integer> codePoints, boolean complete, List<String> warnings) {
    public RequiredCodePointCollection {
        codePoints = Set.copyOf(codePoints == null ? Set.of() : codePoints);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }
}
