package com.myhomelibcorp.application.catalog;

import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Application facade for followed-author state and pending catalog updates. */
@Service
@RequiredArgsConstructor
public class CatalogUpdateService {
    private static final int PAGE_SIZE = 2_000;

    private final CatalogUpdateTrackingPort tracking;

    public void setAuthorFollowed(AuthorId authorId, boolean followed) {
        if (authorId == null) throw new IllegalArgumentException("authorId cannot be null");
        tracking.setAuthorFollowed(authorId, followed);
    }

    public boolean isAuthorFollowed(AuthorId authorId) {
        return authorId != null && tracking.isAuthorFollowed(authorId);
    }

    public long pendingUpdateCount() {
        return tracking.countPendingUpdates();
    }

    /**
     * Builds the Stage 7 Author -> type -> books hierarchy. Database reads are paged so a large
     * pending queue never requires one giant SQL result set. The UI still owns the final tree.
     */
    public CatalogUpdateSnapshot pendingUpdateSnapshot() {
        long expected = tracking.countPendingUpdates();
        if (expected <= 0) return CatalogUpdateSnapshot.empty();

        Map<AuthorKey, MutableGroup> groups = new LinkedHashMap<>();
        long newCount = 0;
        long updatedCount = 0;
        CatalogUpdateCursor cursor = null;

        while (newCount + updatedCount < expected) {
            List<CatalogUpdateItem> page = tracking.findPendingUpdateItems(PAGE_SIZE, cursor);
            if (page.isEmpty()) break;
            for (CatalogUpdateItem item : page) {
                AuthorKey key = new AuthorKey(item.authorId(), item.authorName());
                MutableGroup group = groups.computeIfAbsent(key, ignored -> new MutableGroup());
                if (item.type() == CatalogUpdateType.NEW_BY_FOLLOWED_AUTHOR) {
                    group.newBooks.add(item);
                    newCount++;
                } else if (item.type() == CatalogUpdateType.UPDATED_DOWNLOADED_BOOK) {
                    group.updatedBooks.add(item);
                    updatedCount++;
                }
            }
            cursor = CatalogUpdateCursor.after(page.getLast());
            if (page.size() < PAGE_SIZE) break;
        }

        Comparator<CatalogUpdateItem> byBook = Comparator
                .comparing(CatalogUpdateItem::bookTitle, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CatalogUpdateItem::bookId);
        List<CatalogUpdateAuthorGroup> authors = groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparing(AuthorKey::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AuthorKey::id)))
                .map(entry -> {
                    MutableGroup group = entry.getValue();
                    group.newBooks.sort(byBook);
                    group.updatedBooks.sort(byBook);
                    return new CatalogUpdateAuthorGroup(
                            entry.getKey().id(), entry.getKey().name(),
                            group.newBooks, group.updatedBooks);
                })
                .toList();

        long loaded = newCount + updatedCount;
        return new CatalogUpdateSnapshot(loaded, newCount, updatedCount, authors);
    }

    private record AuthorKey(String id, String name) {
        private AuthorKey {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? "Без автора" : name;
        }
    }

    private static final class MutableGroup {
        private final List<CatalogUpdateItem> newBooks = new ArrayList<>();
        private final List<CatalogUpdateItem> updatedBooks = new ArrayList<>();
    }
}
