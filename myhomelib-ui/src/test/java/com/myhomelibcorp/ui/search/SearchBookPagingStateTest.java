package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.query.search.SearchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchBookPagingStateTest {

    @Test
    void loadCanStartOnlyForActiveRequestWithRemainingRows() {
        SearchBookPagingState state = new SearchBookPagingState();
        state.updateTotal(10);

        assertThat(state.tryBeginLoad(0)).isFalse();

        SearchRequest request = SearchRequest.builder().text("query").limit(5).offset(0).build();
        state.activate(request);

        assertThat(state.tryBeginLoad(5)).isTrue();
        assertThat(state.loading()).isTrue();
        assertThat(state.tryBeginLoad(5)).isFalse();

        state.finishLoad();
        assertThat(state.tryBeginLoad(10)).isFalse();
    }

    @Test
    void resetClearsRequestTotalAndLoadingState() {
        SearchBookPagingState state = new SearchBookPagingState();
        SearchRequest request = SearchRequest.builder().text("query").limit(5).offset(0).build();
        state.activate(request);
        state.updateTotal(12);
        assertThat(state.tryBeginLoad(5)).isTrue();

        state.reset();

        assertThat(state.request()).isNull();
        assertThat(state.total()).isZero();
        assertThat(state.loading()).isFalse();
        assertThat(state.hasMore(0)).isFalse();
    }

    @Test
    void negativeTotalsAreNormalizedToZero() {
        SearchBookPagingState state = new SearchBookPagingState();
        state.activate(SearchRequest.builder().text("query").limit(5).offset(0).build());

        state.updateTotal(-1);

        assertThat(state.total()).isZero();
        assertThat(state.hasMore(0)).isFalse();
    }
}
