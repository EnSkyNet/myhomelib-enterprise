package com.myhomelibcorp.application.util;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/** Stable normalization for application commands that accept nullable/duplicated book selections. */
public final class BookIdListSupport {
    private BookIdListSupport() { }

    public static List<BookId> stableDistinctIgnoringNulls(Collection<? extends BookId> source) {
        return stableDistinctIgnoringNulls(source, Integer.MAX_VALUE, "Too many book ids");
    }

    public static List<BookId> stableDistinctIgnoringNulls(
            Collection<? extends BookId> source, int maxUnique, String limitMessage) {
        if (source == null || source.isEmpty()) return List.of();
        if (maxUnique < 1) throw new IllegalArgumentException("maxUnique must be positive");

        LinkedHashSet<BookId> unique = new LinkedHashSet<>(Math.min(source.size(), maxUnique));
        for (BookId id : source) {
            if (id == null || !unique.add(id)) continue;
            if (unique.size() > maxUnique) {
                throw new IllegalArgumentException(limitMessage == null || limitMessage.isBlank()
                        ? "Too many book ids" : limitMessage);
            }
        }
        return List.copyOf(unique);
    }
}
