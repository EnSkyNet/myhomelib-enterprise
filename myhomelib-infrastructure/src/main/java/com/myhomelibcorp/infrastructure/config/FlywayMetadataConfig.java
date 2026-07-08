package com.myhomelibcorp.infrastructure.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayMetadataConfig {

    @Bean(name = "flywayMetadata")
    public Flyway flywayMetadata(@Qualifier("metadataDataSource") DataSource metadataDataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(metadataDataSource)
                .locations("classpath:db/migration_meta")
                .baselineOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}