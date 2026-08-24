package com.myhomelibcorp.application.catalog;

import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Application facade for Stage 6 followed-author state and pending update queries. */
@Service
@RequiredArgsConstructor
public class CatalogUpdateService {
    private final CatalogUpdateTrackingPort tracking;

    public void followAuthor(String authorId) {
        tracking.setAuthorFollowed(AuthorId.fromString(authorId), true);
    }

    public void unfollowAuthor(String authorId) {
        tracking.setAuthorFollowed(AuthorId.fromString(authorId), false);
    }

    public boolean isAuthorFollowed(String authorId) {
        return tracking.isAuthorFollowed(AuthorId.fromString(authorId));
    }

    public List<CatalogUpdateRecord> pendingUpdates(int limit, int offset) {
        return tracking.findPendingUpdates(limit, offset);
    }

    public long pendingUpdateCount() {
        return tracking.countPendingUpdates();
    }
}
