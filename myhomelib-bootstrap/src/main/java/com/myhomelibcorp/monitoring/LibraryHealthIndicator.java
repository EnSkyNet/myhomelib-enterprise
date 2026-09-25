package com.myhomelibcorp.monitoring;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Runtime health probe owned by the composition-root module.
 *
 * <p>The probe intentionally depends on the application search port rather
 * than Lucene classes. Bootstrap is allowed to compose infrastructure, but it
 * must not leak a storage/search implementation into monitoring contracts.</p>
 */
@Component
@RequiredArgsConstructor
public class LibraryHealthIndicator implements HealthIndicator {

    private final CollectionManager collectionManager;
    private final SearchIndexer searchIndexer;
    private final LibraryOperationCoordinator operationCoordinator;

    @Override
    public Health health() {
        LibraryOperationCoordinator.Lease lease = null;
        try {
            // Keep the active DB and Lucene index pinned to the same collection for the whole probe.
            // Without a QUERY barrier a concurrent SWITCH can publish the new DataSource/index and close
            // the old DataSource between the individual checks, producing a false DOWN or mixed-collection
            // health response.
            lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
            var collection = collectionManager.getCurrentCollection();
            DataSource dataSource = collectionManager.getCurrentDataSource();
            if (collection == null || dataSource == null) {
                lease.markCompleted("");
                return Health.down().withDetail("database", "No active collection").build();
            }

            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(1)) {
                    lease.markCompleted("");
                    return Health.down().withDetail("database", "Connection invalid").build();
                }
            }

            int indexedDocuments = searchIndexer.getDocumentCount();
            lease.markCompleted("");
            return Health.up()
                    .withDetail("database", "connected")
                    .withDetail("search", "accessible")
                    .withDetail("indexedDocuments", indexedDocuments)
                    .withDetail("collection", collection.getName())
                    .build();
        } catch (Exception e) {
            if (lease != null) lease.markFailed(e);
            return Health.down(e).withDetail("error", e.getMessage()).build();
        } finally {
            if (lease != null) lease.close();
        }
    }
}
