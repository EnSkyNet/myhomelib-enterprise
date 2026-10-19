package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.service.UiText;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** User-facing operation results. Never expose coordinator JSON, UUIDs, stack traces or paths. */
final class OperationUserResultText {
    private static final Pattern FIELD = Pattern.compile("\\\"(committed|converted|moved|skipped|conflicts|failed)\\\"\\s*:\\s*(\\d+)");

    private OperationUserResultText() { }

    static String result(OperationCenterEntry entry) {
        if (entry == null) return "";
        if (entry.stage() == OperationStage.FAILED || !entry.errorMessage().isBlank()) {
            return UiText.text("common.error.operation_failed") + " " + UiText.text("common.error.details_in_log");
        }
        if (entry.stage() == OperationStage.CANCELLED) return UiText.text("ui.import.stage.cancelled");
        if (entry.stage() != OperationStage.COMPLETED) return UiText.text("ui.operations.result.in_progress");
        String detail = entry.currentItem().strip();
        // ApplyLibraryOrganizationUseCase reports counters as JSON to the coordinator.
        // Parse only the expected non-negative numbers, not arbitrary JSON from a job.
        if (detail.startsWith("{") && detail.endsWith("}") && detail.contains("\"committed\"")) {
            long committed = number(detail, "committed");
            long moved = number(detail, "moved");
            long converted = number(detail, "converted");
            long skipped = number(detail, "skipped");
            long conflicts = number(detail, "conflicts");
            long failed = number(detail, "failed");
            return UiText.format("ui.operations.result.organized", committed, moved, converted, skipped, conflicts, failed);
        }
        return UiText.text("ui.operations.result.completed");
    }

    private static long number(String json, String key) {
        Matcher matcher = FIELD.matcher(json);
        while (matcher.find()) {
            if (key.equals(matcher.group(1))) {
                try { return Long.parseLong(matcher.group(2)); }
                catch (NumberFormatException ignored) { return 0; }
            }
        }
        return 0;
    }
}
