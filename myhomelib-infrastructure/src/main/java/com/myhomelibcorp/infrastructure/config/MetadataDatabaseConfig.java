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
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setMaximumPoolSize(5);
        ds.setConnectionTimeout(30000);
        ds.setIdleTimeout(300000);
        ds.setMaxLifetime(600000);
        return ds;
    }

    @Bean(name = "metadataJdbcTemplate")
    public JdbcTemplate metadataJdbcTemplate(DataSource metadataDataSource) {
        return new JdbcTemplate(metadataDataSource);
    }
}