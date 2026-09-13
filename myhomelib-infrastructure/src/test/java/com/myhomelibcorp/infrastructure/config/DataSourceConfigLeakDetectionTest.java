package com.myhomelibcorp.infrastructure.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceConfigLeakDetectionTest {

    @Test
    void leakDetectionThresholdIsEnabledWithinConfiguredMaxLifetime() {
        try (HikariDataSource dataSource = new DataSourceConfig().createDataSource()) {
            assertThat(dataSource.getLeakDetectionThreshold()).isGreaterThanOrEqualTo(2_000L);
            assertThat(dataSource.getMaxLifetime()).isGreaterThan(dataSource.getLeakDetectionThreshold());
        }
    }
}
