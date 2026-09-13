package com.myhomelibcorp.application.health;

import com.myhomelibcorp.application.integrity.ArtifactIntegrityReport;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.integrity.ArtifactIntegrityPort;
import com.myhomelibcorp.application.port.out.search.SearchIndexLifecycle;
import com.myhomelibcorp.application.search.SearchIndexHealth;
import com.myhomelibcorp.application.usecase.integrity.DataIntegrityChecker;
import com.myhomelibcorp.application.usecase.integrity.IntegrityReport;
import com.myhomelibcorp.domain.model.collection.Collection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Orchestrates the non-destructive MHL-116 audit and the unified MHL-117 health dashboard snapshot. */
@Service
public class LibraryHealthService {
    private static final int FINDING_LIMIT = 2_000;

    private final DataIntegrityChecker integrityChecker;
    private final ArtifactIntegrityPort artifactIntegrityPort;
    private final CollectionBackupPort backupPort;
    private final SearchIndexLifecycle searchIndexLifecycle;
    private final LibraryOperationCoordinator operationCoordinator;
    private final long backupStaleHours;

    public LibraryHealthService(
            DataIntegrityChecker integrityChecker,
            ArtifactIntegrityPort artifactIntegrityPort,
            CollectionBackupPort backupPort,
            SearchIndexLifecycle searchIndexLifecycle,
            LibraryOperationCoordinator operationCoordinator,
            @Value("${app.health.backup-stale-hours:168}") long backupStaleHours
    ) {
        this.integrityChecker = integrityChecker;
        this.artifactIntegrityPort = artifactIntegrityPort;
        this.backupPort = backupPort;
        this.searchIndexLifecycle = searchIndexLifecycle;
        this.operationCoordinator = operationCoordinator;
        this.backupStaleHours = Math.max(0L, backupStaleHours);
    }

    public LibraryHealthReport refresh() {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.INTEGRITY_AUDIT)) {
            return refreshLocked();
        }
    }

    private LibraryHealthReport refreshLocked() {
        Collection collection = backupPort.getCurrentCollection();
        if (collection == null) throw new IllegalStateException("Колекцію не вибрано");

        ArtifactIntegrityReport artifacts = artifactIntegrityPort.auditIncremental(FINDING_LIMIT);
        IntegrityReport integrity = integrityChecker.check();
        SearchIndexHealth indexHealth = searchIndexLifecycle.currentHealth();
        boolean searchFresh = integrity.luceneIntegrityOk() && indexHealth.fresh();
        String searchDetail = buildSearchDetail(integrity, indexHealth);

        Instant now = Instant.now();
        Optional<Instant> backup = backupPort.latestBackupTime(collection);
        long backupAgeHours = backup.map(value -> Math.max(0L, Duration.between(value, now).toHours())).orElse(-1L);
        boolean backupStale = backup.isEmpty() || (backupStaleHours > 0 && backupAgeHours > backupStaleHours);

        long metadataGaps = integrity.booksWithoutAuthor() + integrity.booksWithoutGenre()
                + integrity.booksWithMissingSeries();
        List<LibraryHealthIssue> issues = buildIssues(artifacts, integrity, metadataGaps,
                searchFresh, searchDetail, backup, backupAgeHours, backupStale);

        return new LibraryHealthReport(
                now,
                artifacts.totalArtifacts(),
                artifacts.missingArtifacts(),
                artifacts.corruptArtifacts(),
                artifacts.changedArtifacts(),
                integrity.duplicateBooks(),
                metadataGaps,
                integrity.sqliteIntegrityOk(),
                searchFresh,
                searchDetail,
                integrity.catalogBooks(),
                integrity.luceneDocuments(),
                backup.orElse(null),
                backupAgeHours,
                backupStale,
                artifacts.inspectedArtifacts(),
                artifacts.reusedArtifacts(),
                artifacts.bytesRead(),
                issues,
                artifacts.findings()
        );
    }

    private List<LibraryHealthIssue> buildIssues(
            ArtifactIntegrityReport artifacts,
            IntegrityReport integrity,
            long metadataGaps,
            boolean searchFresh,
            String searchDetail,
            Optional<Instant> backup,
            long backupAgeHours,
            boolean backupStale
    ) {
        List<LibraryHealthIssue> issues = new ArrayList<>();
        add(issues, LibraryHealthIssueType.MISSING_ARTIFACTS, LibraryHealthSeverity.ERROR,
                artifacts.missingArtifacts(), "Відсутні локальні файли",
                "Фізичний файл або архів для локального artifact не знайдено.",
                "Перевірте шлях/носій або повторно завантажте локальну копію; audit нічого не видаляє автоматично.");
        add(issues, LibraryHealthIssueType.CORRUPT_ARTIFACTS, LibraryHealthSeverity.ERROR,
                artifacts.corruptArtifacts(), "Пошкоджені або нечитабельні artifacts",
                "Файл не читається, archive container пошкоджений або потрібний archive entry недоступний.",
                "Відновіть файл із резервної/віддаленої копії та повторіть перевірку.");
        add(issues, LibraryHealthIssueType.CHANGED_ARTIFACTS, LibraryHealthSeverity.WARNING,
                artifacts.changedArtifacts(), "Вміст локальних artifacts змінився",
                "Поточний розмір або SHA-256 відрізняється від зафіксованого baseline.",
                "Перевірте джерело зміни перед будь-яким оновленням metadata/hash baseline.");
        add(issues, LibraryHealthIssueType.DUPLICATES, LibraryHealthSeverity.WARNING,
                integrity.duplicateBooks(), "Фізичні дублікати",
                "Каталог містить повторні фізичні identity для книг.",
                "Відкрийте «Дублікати книг і файлів...» і перегляньте merge/delete preview.");
        add(issues, LibraryHealthIssueType.METADATA_GAPS, LibraryHealthSeverity.WARNING,
                metadataGaps, "Прогалини metadata",
                "Книги без автора/жанру або з невідомою серією.",
                "Відкрийте редактор metadata або Collection Maintenance і виправте записи після preview.");
        if (!integrity.sqliteIntegrityOk()) {
            issues.add(new LibraryHealthIssue(LibraryHealthIssueType.DATABASE_INTEGRITY, LibraryHealthSeverity.ERROR,
                    1, "SQLite integrity_check не пройдено", integrity.sqliteIntegrityMessage(),
                    "Не виконуйте destructive maintenance; створіть backup і відновіть/перевірте базу."));
        }
        if (!searchFresh) {
            issues.add(new LibraryHealthIssue(LibraryHealthIssueType.STALE_SEARCH_INDEX, LibraryHealthSeverity.WARNING,
                    1, "Пошуковий індекс застарілий", searchDetail,
                    "Запустіть «Перебудувати індекс...» і повторіть перевірку стану."));
        }
        if (backupStale) {
            String detail = backup.isEmpty()
                    ? "Резервну копію для активної колекції не знайдено."
                    : "Вік останньої резервної копії: " + backupAgeHours + " год.";
            issues.add(new LibraryHealthIssue(LibraryHealthIssueType.BACKUP_AGE, LibraryHealthSeverity.WARNING,
                    1, "Резервна копія потребує уваги", detail,
                    "Створіть нову резервну копію активної колекції."));
        }
        return List.copyOf(issues);
    }

    private static void add(List<LibraryHealthIssue> issues, LibraryHealthIssueType type,
                            LibraryHealthSeverity severity, long count, String title, String detail, String action) {
        if (count > 0) issues.add(new LibraryHealthIssue(type, severity, count, title, detail, action));
    }

    private static String buildSearchDetail(IntegrityReport integrity, SearchIndexHealth health) {
        String counts = "SQLite=" + integrity.catalogBooks() + ", Lucene=" + integrity.luceneDocuments();
        if (health.detail() == null || health.detail().isBlank()) return counts;
        return counts + "; " + health.detail();
    }
}
