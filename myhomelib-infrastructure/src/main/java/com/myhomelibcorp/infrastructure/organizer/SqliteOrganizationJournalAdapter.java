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

    @Override public List<OrganizationJournalOperation> incompleteOperations(){
        return jdbc().query("SELECT id,collection_id,profile_id,source_root,destination_root,state,started_at,completed_at,summary_json FROM library_organization_operations WHERE state IN ('PLANNED','APPLYING','FAILED','CANCELLED') ORDER BY started_at DESC",
                (rs,n)->new OrganizationJournalOperation(UUID.fromString(rs.getString("id")),rs.getString("profile_id"),Path.of(rs.getString("source_root")),Path.of(rs.getString("destination_root")),OrganizationOperationState.valueOf(rs.getString("state")),parse(rs.getString("started_at")),parse(rs.getString("completed_at")),rs.getString("summary_json"),safe(rs.getString("collection_id"))));
    }

    @Override public List<OrganizationJournalItem> items(UUID operationId){
        return jdbc().query("SELECT * FROM library_organization_items WHERE operation_id=? ORDER BY item_id",(rs,n)->new OrganizationJournalItem(UUID.fromString(rs.getString("operation_id")),UUID.fromString(rs.getString("item_id")),Path.of(rs.getString("source_path")),rs.getString("source_entry"),path(rs.getString("target_path")),rs.getString("source_sha256"),rs.getString("target_sha256"),rs.getString("action"),OrganizationItemState.valueOf(rs.getString("state")),path(rs.getString("backup_path")),rs.getString("optimization_profile"),rs.getLong("size_before"),rs.getLong("size_after"),rs.getLong("bytes_saved"),rs.getDouble("percent_saved"),rs.getString("optimization_summary_json"),
                rs.getString("target_standard"),rs.getString("device_profile"),rs.getString("compression_backend"),rs.getString("validation_status"),
                rs.getString("error_code"),rs.getString("error_message")),operationId.toString());
    }
    private String text(Path p){return p==null?null:p.toString();} private Path path(String v){return v==null||v.isBlank()?null:Path.of(v);} private String safe(String v){return v==null?"":v;} private String instant(Instant i){return i==null?null:i.toString();} private Instant parse(String v){return v==null||v.isBlank()?null:Instant.parse(v);}
}
