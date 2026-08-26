package com.myhomelibcorp.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class MetadataDatabaseConfig {

    @Value("${app.metadata.db-path:${user.home}/.myhomelibcorp/meta.db}")
    private String metadataDbPath;

    @Primary
    @Bean(name = "metadataDataSource")
    public DataSource metadataDataSource() {
        Path dbPath = Paths.get(metadataDbPath);
        dbPath.getParent().toFile().mkdirs();

        com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setConnectionTimeout(30000);
        config.setConnectionInitSql("PRAGMA foreign_keys=ON;");
        config.setPoolName("HikariPool-MetaDB");

        return new HikariDataSource(config);
    }

    @Bean(name = "metadataJdbcTemplate")
    public JdbcTemplate metadataJdbcTemplate(@Qualifier("metadataDataSource") DataSource metadataDataSource) {
        return new JdbcTemplate(metadataDataSource);
    }

    /**
     * Transaction manager for the metadata database (collections, collection metadata, etc.).
     *
     * This bean must exist explicitly because the application also defines
     * collectionTransactionManager. Once any PlatformTransactionManager bean is present,
     * Spring Boot no longer auto-creates the default DataSource transaction manager.
     * Without this bean, an unqualified @Transactional on metadata repositories can be
     * resolved to the collection-scoped transaction manager and fail before a collection
     * has even been selected.
     */
    @Primary
    @Bean(name = "metadataTransactionManager")
    public PlatformTransactionManager metadataTransactionManager(
            @Qualifier("metadataDataSource") DataSource metadataDataSource) {
        return new DataSourceTransactionManager(metadataDataSource);
    }

    @Bean(name = "metadataTransactionTemplate")
    public TransactionTemplate metadataTransactionTemplate(
            @Qualifier("metadataTransactionManager") PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}