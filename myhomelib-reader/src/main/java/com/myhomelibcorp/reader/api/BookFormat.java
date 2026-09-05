package com.myhomelibcorp.reader.api;

import java.util.Set;
import java.util.Locale;

public interface BookFormat {

    String id();

    default String displayName() {
        return id().toUpperCase(Locale.ROOT);
    }

    Set<String> extensions();

    boolean supports(BookSource source);

    BookParser createParser();

    default boolean isReflowable() {
        return true;
    }

    default boolean isFixedLayout() {
        return !isReflowable();
    }
}
