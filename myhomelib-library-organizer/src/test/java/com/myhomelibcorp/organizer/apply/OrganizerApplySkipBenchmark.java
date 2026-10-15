package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.epub.optimizer.EpubOptimizer;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecision;
import com.myhomelibcorp.organizer.journal.*;
import com.myhomelibcorp.organizer.plan.*;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** On-demand end-to-end skip path microbenchmark. Not part of default *Test suite. */
class OrganizerApplySkipBenchmark {
    @TempDir Path temp;

    @Test void measureSkipOnlyApply() throws Exception {
        int size = Integer.getInteger("mhl.benchmark.books", 1200);
        int runs = Integer.getInteger("mhl.benchmark.runs", 4);
        Path sourceRoot = Files.createDirectories(temp.resolve("source"));
        List<OrganizationPlanItem> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Path source = sourceRoot.resolve("keep-" + i + ".fb2");
            Files.writeString(source, "v" + i);
            items.add(new OrganizationPlanItem(UUID.randomUUID(), source, "", "preview-hash", source,
                    "fb2", "fb2", OrganizationAction.NOOP, false, "unchanged", DuplicateDecision.none(),
                    100, List.of(), List.of()));
        }
        ApplicationSettingsPort settings = new ApplicationSettingsPort() {
            @Override public String get(String k, String d) { return d; }
            @Override public void put(String k, String v) { }
            @Override public void remove(String k) { }
            @Override public Map<String, String> findByPrefix(String k) { return Map.of(); }
        };
        EpubOptimizer optimizer = new EpubOptimizer(null, null, null, null, null, null, null, null, null, List.of());
        List<Long> times = new ArrayList<>();
        for (int run = 0; run <= runs; run++) {
            OrganizationPlan plan = new OrganizationPlan(UUID.randomUUID(), Instant.now(), OrganizerProfile.safeDefault(),
                    sourceRoot, temp.resolve("target"), items, PlanStatistics.from(items), List.of());
            OrganizationJournalPort journal = new OrganizationJournalPort() {
                @Override public void begin(OrganizationJournalOperation operation, List<OrganizationJournalItem> snapshots) { }
                @Override public void updateOperation(UUID id, OrganizationOperationState s, String summary) { }
                @Override public void updateItem(OrganizationJournalItem item) { }
                @Override public void updateItems(List<OrganizationJournalItem> batch) { }
                @Override public List<OrganizationJournalOperation> incompleteOperations() { return List.of(); }
                @Override public List<OrganizationJournalItem> items(UUID id) { return List.of(); }
            };
            ApplyLibraryOrganizationUseCase useCase = new ApplyLibraryOrganizationUseCase(List.of(), journal,
                    new LibraryOperationCoordinator(), List.of(), optimizer, settings,
                    (file, format, maximum) -> { });
            long started = System.nanoTime();
            ApplyResult result = useCase.apply(plan, () -> false);
            long ms = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertThat(result.skipped()).isEqualTo(size);
            assertThat(result.committed()).isZero();
            if (run > 0) times.add(ms);
        }
        Collections.sort(times);
        long medianMs = times.get(times.size() / 2);
        System.out.println("ORGANIZER_SKIP_BENCHMARK size=" + size + " runs=" + runs
                + " medianMs=" + medianMs + " timesMs=" + times);
    }
}
