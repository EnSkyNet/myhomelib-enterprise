package com.myhomelibcorp.application.query.common;

import lombok.Value;

@Value
public class PageRequest {
    int page;
    int size;
    SortBy sortBy;
    SortDirection direction;

    public PageRequest(int page, int size) {
        this(page, size, SortBy.TITLE, SortDirection.ASC);
    }

    public PageRequest(int page, int size, SortBy sortBy, SortDirection direction) {
        this.page = Math.max(0, page);
        this.size = Math.min(1000, Math.max(1, size));
        this.sortBy = sortBy != null ? sortBy : SortBy.TITLE;
        this.direction = direction != null ? direction : SortDirection.ASC;
    }

    public int getOffset() {
        return page * size;
    }

    public static PageRequest firstPage(int size) {
        return new PageRequest(0, size);
    }
}