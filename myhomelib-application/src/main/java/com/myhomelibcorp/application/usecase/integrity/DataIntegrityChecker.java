package com.myhomelibcorp.application.usecase.integrity;

import com.myhomelibcorp.application.port.out.integrity.DataIntegrityPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataIntegrityChecker {

    private final DataIntegrityPort dataIntegrityPort;

    /**
     * Виконує перевірку цілісності даних.
     */
    public IntegrityReport check() {
        log.info("🔍 Starting data integrity check...");
        IntegrityReport report = dataIntegrityPort.checkIntegrity();
        log.info("✅ Integrity check completed: {} issues found", report.issues().size());
        if (report.hasIssues()) {
            log.info("📊 {}", report.getSummary());
            report.issues().forEach(issue -> log.info("  - {}", issue));
        }
        return report;
    }

}