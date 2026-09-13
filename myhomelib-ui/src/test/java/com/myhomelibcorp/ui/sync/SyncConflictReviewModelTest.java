package com.myhomelibcorp.ui.sync;

import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewItem;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncConflictReviewModelTest {
    @Test
    void exposesBothSnapshotsAndExplicitChoiceWithoutDomainDependency() {
        SyncConflictReviewItem item = new SyncConflictReviewItem(
                "setting:x", "SETTING", "review", "local=value-a", "remote=value-b");

        SyncConflictReviewModel model = new SyncConflictReviewModel(List.of(item));

        assertThat(model.rows()).hasSize(1);
        SyncConflictReviewModel.Row row = model.rows().getFirst();
        assertThat(row.item().localSnapshot()).contains("value-a");
        assertThat(row.item().remoteSnapshot()).contains("value-b");
        assertThat(row.selection().side()).isEqualTo(SyncConflictReviewSelection.Side.LOCAL);
        assertThat(row.withChoice(SyncConflictReviewSelection.Side.REMOTE).selection().side())
                .isEqualTo(SyncConflictReviewSelection.Side.REMOTE);
    }
}
