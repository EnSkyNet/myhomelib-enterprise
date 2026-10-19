package com.myhomelibcorp.infrastructure.organizer;

import com.myhomelibcorp.organizer.journal.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SqliteOrganizationJournalAdapter implements OrganizationJournalPort {
    private static final String ITEM_UPSERT_SQL = """
            INSERT INTO library_organization_items(operation_id,item_id,source_path,source_entry,target_path,source_sha256,target_sha256,action,state,backup_path,optimization_profile,size_before,size_after,bytes_saved,percent_saved,optimization_summary_json,target_standard,device_profile,compression_backend,validation_status,error_code,error_message)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(operation_id,item_id) DO UPDATE SET source_entry=excluded.source_entry, target_path=excluded.target_path, source_sha256=excluded.source_sha256,
            target_sha256=excluded.target_sha256, action=excluded.action, state=excluded.state, backup_path=excluded.backup_path,
            optimization_profile=excluded.optimization_profile,size_before=excluded.size_before,size_after=excluded.size_after,
            bytes_saved=excluded.bytes_saved,percent_saved=excluded.percent_saved,optimization_summary_json=excluded.optimization_summary_json,
            target_standard=excluded.target_standard,device_profile=excluded.device_profile,compression_backend=excluded.compression_backend,
            validation_status=excluded.validation_status,error_code=excluded.error_code, error_message=excluded.error_message
            """;
    private final JdbcTemplate journalJdbc;
    private final javax.sql.DataSource journalDataSource;

    public SqliteOrganizationJournalAdapter(@Qualifier("metadataJdbcTemplate") JdbcTemplate journalJdbc,
                                            @Qualifier("metadataDataSource") javax.sql.DataSource journalDataSource,
                                            @Qualifier("flywayMetadata") org.flywaydb.core.Flyway ignoredMigrationDependency) {
        this.journalJdbc = journalJdbc;
        this.journalDataSource = journalDataSource;
    }
    private JdbcTemplate jdbc(){return journalJdbc;}

    @Override public void begin(OrganizationJournalOperation operation,List<OrganizationJournalItem> items){
        new TransactionTemplate(new DataSourceTransactionManager(journalDataSource)).executeWithoutResult(status->{
            jdbc().update("INSERT INTO library_organization_operations(id,collection_id,profile_id,source_root,destination_root,state,started_at,completed_at,summary_json) VALUES(?,?,?,?,?,?,?,?,?)",
                    operation.operationId().toString(),safe(operation.collectionId()),operation.profileId(),text(operation.sourceRoot()),text(operation.destinationRoot()),operation.state().name(),instant(operation.startedAt()),instant(operation.completedAt()),safe(operation.summaryJson()));
            if (items != null && !items.isEmpty()) {
                jdbc().batchUpdate(ITEM_UPSERT_SQL, items.stream().map(this::itemArgs).toList());
            }
        });
    }

    @Override public void updateOperation(UUID operationId,OrganizationOperationState state,String summaryJson){
        String completed=switch(state){case COMPLETED,FAILED,CANCELLED,ROLLED_BACK->Instant.now().toString();default->null;};
        jdbc().update("UPDATE library_organization_operations SET state=?, completed_at=COALESCE(?,completed_at), summary_json=? WHERE id=?",state.name(),completed,safe(summaryJson),operationId.toString());
    }

    @Override public void updateItem(OrganizationJournalItem item){insertOrUpdate(item);}

    /**
     * One SQLite statement checks EVERY persisted field of the old item before
     * recording the rollback. SQLite's IS operator is NULL-safe (unlike =).
     * The affected-row count is the CAS result; an absent row is never inserted.
     */
    @Override public boolean updateItemIfUnchanged(OrganizationJournalItem expected,
                                                    OrganizationJournalItem replacement) {
        java.util.Objects.requireNonNull(expected, "expected");
        java.util.Objects.requireNonNull(replacement, "replacement");
        if (!expected.operationId().equals(replacement.operationId())
                || !expected.itemId().equals(replacement.itemId())
                || replacement.state() != OrganizationItemState.ROLLED_BACK) {
            throw new IllegalArgumentException("Organizer rollback CAS requires unchanged identity and ROLLED_BACK state");
        }
        Object[] old = itemArgs(expected);
        Object[] desired = itemArgs(replacement);
        // Only state and error fields may change; all identity/path/hash/audit
        // fields must remain untouched during Undo journal completion.
        for (int i = 0; i < old.length; i++) {
            if (i != 8 && i != 20 && i != 21 && !java.util.Objects.equals(old[i], desired[i])) {
                throw new IllegalArgumentException("Organizer rollback CAS cannot rewrite item metadata");
            }
        }
        String[] columns = {"source_path", "source_entry", "target_path", "source_sha256", "target_sha256",
                "action", "state", "backup_path", "optimization_profile", "size_before", "size_after",
                "bytes_saved", "percent_saved", "optimization_summary_json", "target_standard",
                "device_profile", "compression_backend", "validation_status", "error_code", "error_message"};
        StringBuilder sql = new StringBuilder("UPDATE library_organization_items SET state=?, error_code=?, error_message=?"
                + " WHERE operation_id=? AND item_id=?");
        Object[] args = new Object[5 + columns.length];
        args[0] = desired[8]; args[1] = desired[20]; args[2] = desired[21];
        args[3] = old[0]; args[4] = old[1];
        for (int i = 0; i < columns.length; i++) {
            sql.append(" AND ").append(columns[i]).append(" IS ?");
            args[i + 5] = old[i + 2];
        }
        return jdbc().update(sql.toString(), args) == 1;
    }

    /**
     * A single conditional UPDATE is the linearization point for completing Undo.
     * JSON1's json_each allows an arbitrarily long UUID set using ONE bound
     * parameter (no SQLite 999/32766 host-parameter ceiling). Each id is a
     * validated UUID from the initial snapshot; item IDs are unique in SQLite.
     * The correlated predicates reject added, removed, replaced or pending items
     * and reject an independently modified operation row.
     */
    @Override public boolean finalizeRollbackIfUnchanged(OrganizationJournalOperation expected,
                                                          List<UUID> expectedItemIds, String summaryJson) {
        java.util.Objects.requireNonNull(expected, "expected");
        java.util.Objects.requireNonNull(expectedItemIds, "expectedItemIds");
        if (expected.state() == OrganizationOperationState.ROLLED_BACK) {
            return false;
        }
        java.util.HashSet<UUID> unique = new java.util.HashSet<>();
        for (UUID id : expectedItemIds) {
            if (id == null || !unique.add(id)) {
                throw new IllegalArgumentException("Organizer finalization requires unique nonnull item IDs");
            }
        }
        String idsJson = expectedItemIds.stream().map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        String sql = """
            UPDATE library_organization_operations
               SET state='ROLLED_BACK', completed_at=?, summary_json=?
             WHERE id=? AND collection_id IS ? AND profile_id IS ?
               AND source_root IS ? AND destination_root IS ?
               AND state IS ? AND started_at IS ? AND completed_at IS ?
               AND summary_json IS ?
               AND (SELECT COUNT(*) FROM library_organization_items WHERE operation_id=?) = ?
               AND NOT EXISTS (
                  SELECT 1 FROM library_organization_items i
                   WHERE i.operation_id=? AND (
                     i.state NOT IN ('ROLLED_BACK','SKIPPED')
                     OR i.item_id NOT IN (SELECT value FROM json_each(?))
                   )
               )
            """;
        return jdbc().update(sql, Instant.now().toString(), safe(summaryJson),
                expected.operationId().toString(), safe(expected.collectionId()), expected.profileId(),
                text(expected.sourceRoot()), text(expected.destinationRoot()), expected.state().name(),
                instant(expected.startedAt()), instant(expected.completedAt()), safe(expected.summaryJson()),
                expected.operationId().toString(), unique.size(), expected.operationId().toString(), idsJson) == 1;
    }

    @Override public void updateItems(List<OrganizationJournalItem> items) {
        if (items == null || items.isEmpty()) return;
        new TransactionTemplate(new DataSourceTransactionManager(journalDataSource)).executeWithoutResult(status ->
                jdbc().batchUpdate(ITEM_UPSERT_SQL, items.stream().map(this::itemArgs).toList()));
    }

    private void insertOrUpdate(OrganizationJournalItem item){
        jdbc().update(ITEM_UPSERT_SQL, itemArgs(item));
    }

    private Object[] itemArgs(OrganizationJournalItem item) {
        return new Object[]{
                item.operationId().toString(), item.itemId().toString(), text(item.sourcePath()), safe(item.sourceEntry()),
                text(item.targetPath()), safe(item.sourceSha256()), safe(item.targetSha256()), safe(item.action()),
                item.state().name(), text(item.backupPath()), safe(item.optimizationProfile()), item.sizeBefore(),
                item.sizeAfter(), item.bytesSaved(), item.percentSaved(), safe(item.optimizationSummaryJson()),
                safe(item.targetStandard()), safe(item.deviceProfile()), safe(item.compressionBackend()), safe(item.validationStatus()),
                safe(item.errorCode()), safe(item.errorMessage())
        };
    }

    @Override public Optional<OrganizationJournalOperation> findOperation(UUID operationId) {
        java.util.Objects.requireNonNull(operationId, "operationId");
        List<OrganizationJournalOperation> records = jdbc().query(
                "SELECT id,collection_id,profile_id,source_root,destination_root,state,started_at,completed_at,summary_json "
                        + "FROM library_organization_operations WHERE id=?",
                (rs, n) -> new OrganizationJournalOperation(UUID.fromString(rs.getString("id")),
                        rs.getString("profile_id"), Path.of(rs.getString("source_root")),
                        Path.of(rs.getString("destination_root")),
                        OrganizationOperationState.valueOf(rs.getString("state")),
                        parse(rs.getString("started_at")), parse(rs.getString("completed_at")),
                        rs.getString("summary_json"), safe(rs.getString("collection_id"))),
                operationId.toString());
        return records.stream().findFirst();
    }

    @Override public List<OrganizationJournalOperation> incompleteOperations(){
        return jdbc().query("SELECT id,collection_id,profile_id,source_root,destination_root,state,started_at,completed_at,summary_json FROM library_organization_operations WHERE state IN ('PLANNED','APPLYING','FAILED','CANCELLED') ORDER BY started_at DESC",
                (rs,n)->new OrganizationJournalOperation(UUID.fromString(rs.getString("id")),rs.getString("profile_id"),Path.of(rs.getString("source_root")),Path.of(rs.getString("destination_root")),OrganizationOperationState.valueOf(rs.getString("state")),parse(rs.getString("started_at")),parse(rs.getString("completed_at")),rs.getString("summary_json"),safe(rs.getString("collection_id"))));
    }

    @Override public List<OrganizationJournalItem> items(UUID operationId){
        return jdbc().query("SELECT * FROM library_organization_items WHERE operation_id=? ORDER BY item_id",
                this::readItem, operationId.toString());
    }

    /** Keyed read avoids scanning the full organization journal before each Undo item. */
    @Override public Optional<OrganizationJournalItem> findItem(UUID operationId, UUID itemId) {
        List<OrganizationJournalItem> rows = jdbc().query(
                "SELECT * FROM library_organization_items WHERE operation_id=? AND item_id=? LIMIT 2",
                this::readItem, operationId.toString(), itemId.toString());
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    private OrganizationJournalItem readItem(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new OrganizationJournalItem(UUID.fromString(rs.getString("operation_id")),
                UUID.fromString(rs.getString("item_id")), Path.of(rs.getString("source_path")),
                rs.getString("source_entry"), path(rs.getString("target_path")),
                rs.getString("source_sha256"), rs.getString("target_sha256"), rs.getString("action"),
                OrganizationItemState.valueOf(rs.getString("state")), path(rs.getString("backup_path")),
                rs.getString("optimization_profile"), rs.getLong("size_before"),
                rs.getLong("size_after"), rs.getLong("bytes_saved"), rs.getDouble("percent_saved"),
                rs.getString("optimization_summary_json"), rs.getString("target_standard"),
                rs.getString("device_profile"), rs.getString("compression_backend"),
                rs.getString("validation_status"), rs.getString("error_code"),
                rs.getString("error_message"));
    }
    private String text(Path p){return p==null?null:p.toString();} private Path path(String v){return v==null||v.isBlank()?null:Path.of(v);} private String safe(String v){return v==null?"":v;} private String instant(Instant i){return i==null?null:i.toString();} private Instant parse(String v){return v==null||v.isBlank()?null:Instant.parse(v);}
}
