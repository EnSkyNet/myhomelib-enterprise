package com.myhomelibcorp.reader.api;

import java.util.Set;

public interface BookFormat {

    String id();

    default String displayName() {
        return id().toUpperCase();
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