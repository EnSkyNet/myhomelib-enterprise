package com.myhomelibcorp.application.query.common;

import java.util.List;

public record PageResult<T>(List<T> content, long totalElements, int totalPages, int currentPage, int size) {

    public boolean hasNext() {
        return currentPage < totalPages - 1;
    }

    public boolean hasPrevious() {
        return currentPage > 0;
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(List.of(), 0, 0, 0, 0);
    }
}