package com.myhomelibcorp.ui.sync;

import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewItem;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncConflictReviewModelTest {
    @Test
    void exposesBothLocalizedSnapshotDescriptorsAndExplicitChoiceWithoutDomainDependency() {
        SyncConflictReviewItem item = new SyncConflictReviewItem(
                "setting:x",
                SyncConflictReviewItem.Text.of("ui.sync.entity.setting"),
                SyncConflictReviewItem.Text.of("ui.sync.conflict.reason.manual"),
                SyncConflictReviewItem.Text.of("ui.sync.conflict.snapshot.value", "value-a", "hash-a"),
                SyncConflictReviewItem.Text.of("ui.sync.conflict.snapshot.value", "value-b", "hash-b"));

        SyncConflictReviewModel model = new SyncConflictReviewModel(List.of(item));

        assertThat(model.rows()).hasSize(1);
        SyncConflictReviewModel.Row row = model.rows().getFirst();
        assertThat(row.item().localSnapshot().key()).isEqualTo("ui.sync.conflict.snapshot.value");
        assertThat(row.item().localSnapshot().arguments()).contains("value-a");
        assertThat(row.item().remoteSnapshot().arguments()).contains("value-b");
        assertThat(row.selection().side()).isEqualTo(SyncConflictReviewSelection.Side.LOCAL);
        assertThat(row.withChoice(SyncConflictReviewSelection.Side.REMOTE).selection().side())
                .isEqualTo(SyncConflictReviewSelection.Side.REMOTE);
    }
}
