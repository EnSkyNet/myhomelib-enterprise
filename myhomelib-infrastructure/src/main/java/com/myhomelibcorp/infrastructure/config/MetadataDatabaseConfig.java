package com.myhomelibcorp.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

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
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        config.setConnectionTimeout(30000);
        config.setPoolName("HikariPool-MetaDB");

        return new HikariDataSource(config);
    }

    @Bean(name = "metadataJdbcTemplate")
    public JdbcTemplate metadataJdbcTemplate(DataSource metadataDataSource) {
        return new JdbcTemplate(metadataDataSource);
    }
}