package com.myhomelibcorp.monitoring;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.apache.lucene.store.Directory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
@RequiredArgsConstructor
public class LibraryHealthIndicator implements HealthIndicator {

    private final CollectionManager collectionManager;
    private final Directory luceneDirectory;

    @Override
    public Health health() {
        try {
            DataSource ds = collectionManager.getCurrentDataSource();
            if (ds == null) {
                return Health.down().withDetail("database", "No active collection").build();
            }
            try (Connection conn = ds.getConnection()) {
                if (!conn.isValid(1)) {
                    return Health.down().withDetail("database", "Connection invalid").build();
                }
            }
            if (luceneDirectory == null) {
                return Health.down().withDetail("lucene", "Index directory is null").build();
            }
            return Health.up()
                    .withDetail("database", "connected")
                    .withDetail("lucene", "accessible")
                    .withDetail("collection", collectionManager.getCurrentCollection() != null ?
                            collectionManager.getCurrentCollection().getName() : "none")
                    .build();
        } catch (Exception e) {
            return Health.down(e).withDetail("error", e.getMessage()).build();
        }
    }
}