package com.myhomelibcorp.infrastructure.config;

import com.myhomelibcorp.infrastructure.cleanup.DatabaseConnectionCleanup;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DatabaseCleanupConfig {

    private final DatabaseConnectionCleanup cleanup;

    @PreDestroy
    public void onShutdown() {
        log.info("🔄 Завершення роботи: очищення ресурсів...");
        cleanup.cleanupAll();
        log.info("✅ Ресурси очищено");
    }
}