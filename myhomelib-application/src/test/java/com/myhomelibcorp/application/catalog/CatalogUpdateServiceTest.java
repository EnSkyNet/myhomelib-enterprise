package com.myhomelibcorp.application.catalog;

import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogUpdateServiceTest {

    @Test
    void groupsPendingItemsByAuthorAndTypeWithoutDuplicatingCounters() {
        CatalogUpdateTrackingPort tracking = mock(CatalogUpdateTrackingPort.class);
        when(tracking.countPendingUpdates()).thenReturn(3L);
        when(tracking.findPendingUpdateItems(2000, null)).thenReturn(List.of(
                new CatalogUpdateItem("b2", "Zulu", "a1", "Author One", CatalogUpdateType.UPDATED_DOWNLOADED_BOOK, true, "2026-08-25"),
                new CatalogUpdateItem("b1", "Alpha", "a1", "Author One", CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR, false, "2026-08-25"),
                new CatalogUpdateItem("b3", "Beta", "a2", "Author Two", CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR, false, "2026-08-25")
        ));

        CatalogUpdateSnapshot snapshot = new CatalogUpdateService(tracking).pendingUpdateSnapshot();

        assertThat(snapshot.totalCount()).isEqualTo(3);
        assertThat(snapshot.newCount()).isEqualTo(2);
        assertThat(snapshot.updatedCount()).isEqualTo(1);
        assertThat(snapshot.authors()).extracting(CatalogUpdateAuthorGroup::authorName)
                .containsExactly("Author One", "Author Two");
        assertThat(snapshot.authors().getFirst().newBooks()).extracting(CatalogUpdateItem::bookTitle)
                .containsExactly("Alpha");
        assertThat(snapshot.authors().getFirst().updatedBooks()).extracting(CatalogUpdateItem::bookTitle)
                .containsExactly("Zulu");
    }
}
