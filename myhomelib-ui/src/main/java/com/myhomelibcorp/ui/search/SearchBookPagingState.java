package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.query.search.SearchRequest;

/** Mutable paging state for book results in the search workspace. */
final class SearchBookPagingState {
    private SearchRequest request;
    private long total;
    private boolean loading;

    SearchRequest request() {
        return request;
    }

    long total() {
        return total;
    }

    boolean loading() {
        return loading;
    }

    void activate(SearchRequest request) {
        this.request = request;
        this.loading = false;
    }

    void updateTotal(long total) {
        this.total = Math.max(0L, total);
    }

    boolean tryBeginLoad(int loaded) {
        if (!hasMore(loaded) || loading) return false;
        loading = true;
        return true;
    }

    void finishLoad() {
        loading = false;
    }

    boolean hasMore(int loaded) {
        return request != null && Math.max(0, loaded) < total;
    }

    void reset() {
        request = null;
        total = 0L;
        loading = false;
    }
}
