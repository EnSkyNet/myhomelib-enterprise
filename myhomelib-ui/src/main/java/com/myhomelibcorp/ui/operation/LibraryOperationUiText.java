package com.myhomelibcorp.ui.operation;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.progress.OperationStage;

/** Localized user-facing text for serialized/background library operations. */
public final class LibraryOperationUiText {
    private LibraryOperationUiText() { }

    public static String activeStatus(LibraryOperationType operation) {
        if (operation == null) return "";
        return switch (operation) {
            case INDEX -> UiText.text("ui.java.library_operation_ui_text.c2b9974c8f");
            case UPDATE -> UiText.text("ui.java.library_operation_ui_text.fe77681395");
            case IMPORT -> UiText.text("ui.java.library_operation_ui_text.f8d08d8feb");
            case BACKUP -> UiText.text("ui.java.library_operation_ui_text.3cbae27e4d");
            case RESTORE -> UiText.text("ui.java.library_operation_ui_text.75322e22e4");
            case VACUUM -> UiText.text("ui.java.library_operation_ui_text.7b6c3952d5");
            case SWITCH -> UiText.text("ui.java.library_operation_ui_text.ed45ccb5d7");
            case DELETE -> UiText.text("ui.java.library_operation_ui_text.86622c9554");
            case CREATE -> UiText.text("ui.java.library_operation_ui_text.703d5974b7");
            case SYNC -> UiText.text("ui.java.library_operation_ui_text.43d605d3a8");
            case DOWNLOAD -> UiText.text("ui.java.library_operation_ui_text.a3c6cfd395");
            case CONVERSION -> UiText.text("ui.java.library_operation_ui_text.273d78eda4");
            case EDIT -> UiText.text("ui.operation.coordinator.edit");
            case QUERY -> UiText.text("ui.operation.coordinator.query");
            case READER_STATE -> UiText.text("ui.operation.coordinator.reader_state");
            case ANNOTATION -> UiText.text("ui.operation.coordinator.annotation");
            case COPY -> UiText.text("ui.operation.coordinator.copy");
            case EXPORT -> UiText.text("ui.operation.coordinator.export");
            case INTEGRITY_AUDIT -> UiText.text("ui.java.library_operation_ui_text.c1ece3ee06");
            case ORGANIZE -> UiText.text("ui.organizer.title");
        };
    }

    public static String blockingMessage(String requestedAction, LibraryOperationType activeOperation) {
        String requested = requestedAction == null || requestedAction.isBlank() ? UiText.text("ui.fxml.operation_center_workspace.operation") : requestedAction.trim();
        String active = activeOperationName(activeOperation);
        return requested + UiText.text("ui.java.library_operation_ui_text.d6e54fe884") + active + UiText.text("ui.java.library_operation_ui_text.6f3f34ffee");
    }

    public static String entryStatus(OperationCenterEntry entry) {
        if (entry == null) return UiText.text("ui.java.library_operation_ui_text.7d12d8cbba");
        // Coordinator-only background work has no dedicated progress stage. Preserve
        // its specific operation title rather than saying only "Checking" or "Working".
        if (entry.operationId().startsWith("coordinator-")) return entry.title();
        String base = switch (entry.stage()) {
            case CHECKING_SERVER -> UiText.text("ui.java.library_operation_ui_text.c0edea5d0a");
            case DOWNLOADING -> UiText.text("ui.java.library_operation_ui_text.d6556511fd");
            case VALIDATING -> UiText.text("ui.java.library_operation_ui_text.d730769fee");
            case CREATING_CHECKPOINT -> UiText.text("ui.java.library_operation_ui_text.579450e677");
            case READING_CATALOG -> UiText.text("ui.java.library_operation_ui_text.5a0446f84a");
            case IMPORTING -> UiText.text("ui.java.library_operation_ui_text.4d3d9427fe");
            case UPDATING_AUTHORS -> UiText.text("ui.java.library_operation_ui_text.0245d6fcb5");
            case APPLYING_DELETIONS -> UiText.text("ui.java.library_operation_ui_text.6aa46441ef");
            case UPDATING_SEARCH_INDEX -> UiText.text("ui.java.library_operation_ui_text.860c7231f7");
            case REFRESHING_STATISTICS -> UiText.text("ui.java.library_operation_ui_text.ba2ddc9286");
            case ROLLING_BACK -> UiText.text("ui.history.undo_progress");
            case INTEGRITY_CHECKS -> UiText.text("ui.java.library_operation_ui_text.c1ece3ee06");
            case SCANNING_DUPLICATES -> UiText.text("ui.java.library_operation_ui_text.47b5420b40");
            case SYNCHRONIZING_FILES -> UiText.text("ui.java.library_operation_ui_text.67ed37311c");
            case OPTIMIZING_DATABASE -> UiText.text("ui.java.library_operation_ui_text.7b6c3952d5");
            case BACKING_UP -> UiText.text("ui.java.library_operation_ui_text.3cbae27e4d");
            case RESTORING -> UiText.text("ui.java.library_operation_ui_text.fb27736858");
            case CREATING_COLLECTION -> UiText.text("ui.java.library_operation_ui_text.703d5974b7");
            case DELETING_COLLECTION -> UiText.text("ui.java.library_operation_ui_text.86622c9554");
            case FINALIZING -> UiText.text("ui.java.library_operation_ui_text.30a8f3e716");
            case BOOK_DOWNLOAD -> UiText.text("ui.java.library_operation_ui_text.a3c6cfd395");
            case CONVERTING -> UiText.text("ui.java.library_operation_ui_text.273d78eda4");
            case COMPLETED, CANCELLED, FAILED -> entry.title();
        };
        double fraction = entry.fraction();
        if (fraction >= 0.0 && fraction <= 1.0) {
            int percent = (int) Math.round(fraction * 100.0);
            return base + " " + percent + "%";
        }
        // Do not append producer-supplied internal paths, JSON or identifiers to the status bar.
        return base;
    }

    public static boolean matches(LibraryOperationType operation, OperationCenterEntry entry) {
        if (operation == null || entry == null || !entry.active()) return false;
        return switch (operation) {
            case INDEX -> entry.kind() == OperationKind.INDEX_REBUILD || entry.stage() == OperationStage.UPDATING_SEARCH_INDEX;
            case UPDATE -> entry.kind() == OperationKind.CATALOG_UPDATE;
            case IMPORT -> entry.kind() == OperationKind.CATALOG_IMPORT || entry.kind() == OperationKind.CATALOG_UPDATE;
            case BACKUP -> entry.kind() == OperationKind.BACKUP;
            case RESTORE -> entry.kind() == OperationKind.RESTORE;
            case CREATE -> entry.kind() == OperationKind.COLLECTION_CREATE;
            case DELETE -> entry.kind() == OperationKind.COLLECTION_DELETE;
            case INTEGRITY_AUDIT -> entry.kind() == OperationKind.INTEGRITY_CHECK;
            case DOWNLOAD -> entry.kind() == OperationKind.BOOK_DOWNLOAD;
            case CONVERSION -> entry.kind() == OperationKind.BOOK_CONVERSION;
            case ORGANIZE -> entry.kind() == OperationKind.LIBRARY_ORGANIZATION;
            case VACUUM, SYNC, SWITCH, EDIT, ANNOTATION, COPY, EXPORT, QUERY, READER_STATE ->
                    entry.kind() == OperationKind.MAINTENANCE || entry.kind() == OperationKind.GENERIC;
        };
    }

    private static String activeOperationName(LibraryOperationType operation) {
        if (operation == null) return UiText.text("ui.java.library_operation_ui_text.fb62fcd042");
        return switch (operation) {
            case INDEX -> UiText.text("ui.java.library_operation_ui_text.027dac2425");
            case UPDATE -> UiText.text("ui.java.library_operation_ui_text.c9522cfdf0");
            case IMPORT -> UiText.text("ui.java.library_operation_ui_text.3e30a7f744");
            case BACKUP -> UiText.text("ui.java.library_operation_ui_text.a963bf2a71");
            case RESTORE -> UiText.text("ui.java.library_operation_ui_text.3546b21546");
            case VACUUM -> UiText.text("ui.java.library_operation_ui_text.a5f1516263");
            case SWITCH -> UiText.text("ui.java.library_operation_ui_text.62ba38acc9");
            case DELETE -> UiText.text("ui.java.library_operation_ui_text.925caf3458");
            case CREATE -> UiText.text("ui.java.library_operation_ui_text.3a7f411870");
            case SYNC -> UiText.text("ui.java.library_operation_ui_text.8e3a66c7e5");
            case DOWNLOAD -> UiText.text("ui.import.stage.book_download");
            case CONVERSION -> UiText.text("ui.java.library_operation_ui_text.273d78eda4");
            case EDIT, ANNOTATION, COPY, EXPORT, QUERY, READER_STATE -> UiText.text("ui.java.library_operation_ui_text.fb62fcd042");
            case INTEGRITY_AUDIT -> UiText.text("ui.java.library_operation_ui_text.cc9c7e7ace");
            case ORGANIZE -> UiText.text("ui.organizer.title");
        };
    }
}
