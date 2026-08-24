package com.myhomelibcorp.monitoring;

import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
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

    @Override
    public Health health() {
        try {
            DataSource dataSource = collectionManager.getCurrentDataSource();
            if (dataSource == null) {
                return Health.down().withDetail("database", "No active collection").build();
            }

            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(1)) {
                    return Health.down().withDetail("database", "Connection invalid").build();
                }
            }

            int indexedDocuments = searchIndexer.getDocumentCount();
            return Health.up()
                    .withDetail("database", "connected")
                    .withDetail("search", "accessible")
                    .withDetail("indexedDocuments", indexedDocuments)
                    .withDetail("collection", collectionManager.getCurrentCollection() != null
                            ? collectionManager.getCurrentCollection().getName()
                            : "none")
                    .build();
        } catch (Exception e) {
            return Health.down(e).withDetail("error", e.getMessage()).build();
        }
    }
}
