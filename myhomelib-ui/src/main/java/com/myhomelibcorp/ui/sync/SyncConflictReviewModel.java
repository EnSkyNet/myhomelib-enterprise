package com.myhomelibcorp.ui.sync;

import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewItem;
import com.myhomelibcorp.application.sync.conflict.SyncConflictReviewSelection;

import java.util.List;
import java.util.Objects;

/** Headless-friendly UI model that never imports domain entities. */
public final class SyncConflictReviewModel {
    public record Row(SyncConflictReviewItem item, SyncConflictReviewSelection.Side choice) {
        public Row {
            Objects.requireNonNull(item, "item");
            if (choice == null) choice = SyncConflictReviewSelection.Side.LOCAL;
        }

        public Row withChoice(SyncConflictReviewSelection.Side newChoice) {
            return new Row(item, Objects.requireNonNull(newChoice));
        }

        public SyncConflictReviewSelection selection() {
            return new SyncConflictReviewSelection(item.logicalKey(), choice);
        }
    }

    private final List<Row> rows;

    public SyncConflictReviewModel(List<SyncConflictReviewItem> conflicts) {
        if (conflicts == null) conflicts = List.of();
        this.rows = conflicts.stream().map(item -> new Row(item, SyncConflictReviewSelection.Side.LOCAL)).toList();
    }

    public List<Row> rows() { return rows; }
}
