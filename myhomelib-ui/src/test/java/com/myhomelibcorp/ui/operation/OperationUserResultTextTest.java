package com.myhomelibcorp.ui.operation;

import com.myhomelibcorp.application.progress.OperationStage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class OperationUserResultTextTest {
    private static OperationCenterEntry entry(OperationStage stage, String details, String error) {
        Instant now = Instant.now();
        return new OperationCenterEntry("debug-id", "Упорядкування бібліотеки", "internal-collection-uuid",
                OperationKind.GENERIC, stage, 0, 0, 0, 0, 0, 0, 0, 0, 0, details, false,
                now, now, now, error);
    }

    @Test void organizerJsonIsLocalizedAndDoesNotLeakImplementationDetails() {
        String result = OperationUserResultText.result(entry(OperationStage.COMPLETED,
                "{\"committed\":12784,\"converted\":0,\"moved\":12784,\"skipped\":0,\"conflicts\":0,\"failed\":0}", ""));
        assertThat(result).contains("12", "12784").doesNotContain("committed", "{", "uuid", "internal-");
    }

    @Test void errorsAndArbitraryRawValuesAreNeverPresented() {
        assertThat(OperationUserResultText.result(entry(OperationStage.FAILED, "", "java.lang.IllegalStateException: disk")))
                .doesNotContain("java.lang", "disk");
        assertThat(OperationUserResultText.result(entry(OperationStage.COMPLETED, "C:\\temp\\secret.fb2", "")))
                .doesNotContain("secret.fb2", "C:");
    }
}
