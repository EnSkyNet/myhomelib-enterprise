package com.myhomelibcorp.ui.annotation;

import com.myhomelibcorp.application.annotation.AnnotationManagerItem;
import com.myhomelibcorp.application.annotation.AnnotationManagerType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationManagerUnicodeBoundaryTest {

    @Test
    void summaryDoesNotSplitSurrogatePairAtTruncationBoundary() throws Exception {
        String note = "a".repeat(176) + "😀" + "b".repeat(10);
        AnnotationManagerItem item = new AnnotationManagerItem(
                "annotation-1", "book-1", "Book", AnnotationManagerType.NOTE,
                "", note, List.of(), "", "", 0.5, Instant.EPOCH, Instant.EPOCH);

        Method summary = AnnotationManagerWorkspaceController.class
                .getDeclaredMethod("summaryText", AnnotationManagerItem.class);
        summary.setAccessible(true);
        String value = (String) summary.invoke(null, item);

        assertThat(value).endsWith("…");
        assertThat(hasUnpairedSurrogate(value)).isFalse();
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
