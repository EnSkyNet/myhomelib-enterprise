package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.progress.OperationStage;

/** Canonical localization keys for long-running operation stages. */
public final class OperationStageText {
    private OperationStageText() {
    }

    public static String key(OperationStage stage) {
        if (stage == null) throw new IllegalArgumentException("stage must not be null");
        return switch (stage) {
            case CHECKING_SERVER -> "ui.import.stage.checking_server";
            case DOWNLOADING -> "ui.import.stage.downloading";
            case CREATING_CHECKPOINT -> "ui.import.stage.creating_checkpoint";
            case VALIDATING -> "ui.import.stage.validating";
            case READING_CATALOG -> "ui.import.stage.reading_catalog";
            case IMPORTING -> "ui.import.stage.importing";
            case UPDATING_AUTHORS -> "ui.import.stage.updating_authors";
            case APPLYING_DELETIONS -> "ui.import.stage.applying_deletions";
            case UPDATING_SEARCH_INDEX -> "ui.import.stage.updating_index";
            case ROLLING_BACK -> "ui.import.stage.rolling_back";
            case REFRESHING_STATISTICS -> "ui.import.stage.refreshing_statistics";
            case INTEGRITY_CHECKS -> "ui.import.stage.integrity_checks";
            case SCANNING_DUPLICATES -> "ui.import.stage.scanning_duplicates";
            case SYNCHRONIZING_FILES -> "ui.import.stage.synchronizing_files";
            case OPTIMIZING_DATABASE -> "ui.import.stage.optimizing_database";
            case BACKING_UP -> "ui.import.stage.backing_up";
            case RESTORING -> "ui.import.stage.restoring";
            case CREATING_COLLECTION -> "ui.import.stage.creating_collection";
            case DELETING_COLLECTION -> "ui.import.stage.deleting_collection";
            case FINALIZING -> "ui.import.stage.finalizing";
            case BOOK_DOWNLOAD -> "ui.import.stage.book_download";
            case CONVERTING -> "ui.import.stage.converting";
            case COMPLETED -> "ui.import.stage.completed";
            case CANCELLED -> "ui.import.stage.cancelled";
            case FAILED -> "ui.import.stage.failed";
        };
    }

    public static String text(OperationStage stage) {
        return UiText.text(key(stage));
    }
}
