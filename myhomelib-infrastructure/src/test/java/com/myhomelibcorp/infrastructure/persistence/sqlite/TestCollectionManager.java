package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Тестова реалізація CollectionManager для ізольованих тестів.
 * Дозволяє встановлювати поточну колекцію, DataSource та JdbcTemplate через рефлексію.
 */
public class TestCollectionManager extends CollectionManager {

    public TestCollectionManager(JdbcTemplate metadataJdbcTemplate) {
        super(metadataJdbcTemplate, new TestDataSourceConfig());
    }

    /**
     * Тестова реалізація DataSourceConfig для ізольованих тестів.
     */
    private static class TestDataSourceConfig extends DataSourceConfig {
        @Override
        public HikariDataSource createDataSourceForPath(String dbPath) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:file:testdb?mode=memory&cache=shared");
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            config.setIdleTimeout(30000);
            config.setMaxLifetime(60000);
            config.setConnectionTimeout(10000);
            config.setLeakDetectionThreshold(5000);
            config.setPoolName("TestPool");
            return new HikariDataSource(config);
        }
    }

    public void setCurrentCollection(Collection collection) {
        try {
            Field field = CollectionManager.class.getDeclaredField("currentCollection");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            AtomicReference<Collection> ref = (AtomicReference<Collection>) field.get(this);
            ref.set(collection);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set currentCollection", e);
        }
    }

    public void setCurrentDataSource(DataSource dataSource) {
        try {
            Field field = CollectionManager.class.getDeclaredField("currentDataSource");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            AtomicReference<DataSource> ref = (AtomicReference<DataSource>) field.get(this);
            ref.set(dataSource);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set currentDataSource", e);
        }
    }

    public void setCurrentJdbcTemplate(JdbcTemplate jdbcTemplate) {
        try {
            Field field = CollectionManager.class.getDeclaredField("currentJdbcTemplate");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            AtomicReference<JdbcTemplate> ref = (AtomicReference<JdbcTemplate>) field.get(this);
            ref.set(jdbcTemplate);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set currentJdbcTemplate", e);
        }
    }
}