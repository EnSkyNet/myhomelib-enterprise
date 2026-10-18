package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.epub.optimizer.EpubOptimizer;
import com.myhomelibcorp.organizer.apply.ApplyLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.apply.OrganizerOutputValidationPort;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecision;
import com.myhomelibcorp.organizer.journal.*;
import com.myhomelibcorp.organizer.plan.*;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Forked production Apply worker. Expected to exit via Runtime.halt(83), never via JUnit teardown. */
public final class SqliteOrganizerApplyCrashWorkerV27 {
    private SqliteOrganizerApplyCrashWorkerV27() { }
    public static void main(String[] args) throws Exception {
        Path database = Path.of(args[0]);
        Path source = Path.of(args[1]);
        Path target = Path.of(args[2]);
        String hash = args[3];
        UUID operationId = UUID.fromString(args[4]);
        UUID itemId = UUID.fromString(args[5]);
        String killpoint = args[6];

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + database);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA synchronous=FULL");
        SqliteOrganizationJournalAdapter delegate = new SqliteOrganizationJournalAdapter(jdbc, ds, null);
        OrganizationJournalPort journal = new OrganizationJournalPort() {
            @Override public void begin(OrganizationJournalOperation op, List<OrganizationJournalItem> items) {
                delegate.begin(op, items);
            }
            @Override public void updateOperation(UUID id, OrganizationOperationState state, String summary) {
                delegate.updateOperation(id, state, summary);
            }
            @Override public void updateItem(OrganizationJournalItem item) {
                String phase = item.state().name();
                if (killpoint.equals("BEFORE_" + phase)) Runtime.getRuntime().halt(83);
                delegate.updateItem(item);
                if (killpoint.equals("AFTER_" + phase)) Runtime.getRuntime().halt(83);
            }
            @Override public java.util.Optional<OrganizationJournalOperation> findOperation(UUID id) {return delegate.findOperation(id);}
            @Override public List<OrganizationJournalItem> items(UUID id) {return delegate.items(id);}
            @Override public List<OrganizationJournalOperation> incompleteOperations() {return delegate.incompleteOperations();}
        };
        OrganizationPlanItem item = new OrganizationPlanItem(itemId, source, "", hash, target,
                "fb2", "fb2", OrganizationAction.MOVE, false, "test move", DuplicateDecision.none(),
                100, List.of(), List.of());
        OrganizationPlan plan = new OrganizationPlan(operationId, Instant.now(), OrganizerProfile.safeDefault(),
                source.getParent(), target.getParent(), List.of(item), PlanStatistics.from(List.of(item)), List.of());
        EpubOptimizer optimizer = new EpubOptimizer(null, null, null, null, null, null, null, null, null, List.of());
        OrganizerOutputValidationPort validation = (file, format, maxSize) -> { };
        new ApplyLibraryOrganizationUseCase(List.of(), journal, new LibraryOperationCoordinator(), List.of(),
                optimizer, null, validation).apply(plan, () -> false);
        System.err.println("Worker escaped requested killpoint " + killpoint);
        System.exit(92);
    }
}
