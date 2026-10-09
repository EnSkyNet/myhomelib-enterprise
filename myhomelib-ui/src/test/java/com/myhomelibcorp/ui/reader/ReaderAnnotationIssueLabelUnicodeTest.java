package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.annotation.AnnotationAnchorData;
import com.myhomelibcorp.application.annotation.AnnotationReaderItem;
import com.myhomelibcorp.application.annotation.AnnotationReaderType;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Answers.CALLS_REAL_METHODS;

class ReaderAnnotationIssueLabelUnicodeTest {

    @Test
    void issueLabelDoesNotSplitSurrogatePairAtPreviewBoundary() throws Exception {
        NewReaderWorkspaceController controller = mock(NewReaderWorkspaceController.class, CALLS_REAL_METHODS);
        LocalizationService i18n = mock(LocalizationService.class);
        when(i18n.text(anyString())).thenReturn("kind");
        setField(controller, "i18n", i18n);

        String note = "a".repeat(86) + "😀tail";
        AnnotationAnchorData anchor = new AnnotationAnchorData(
                "book", "artifact", "chapter", "Chapter", "paragraph",
                0, 1, 0.0, "quote", "", "");
        AnnotationReaderItem source = new AnnotationReaderItem(
                "annotation", anchor, "#FFF59D", AnnotationReaderType.NOTE, note, Set.of());
        ReaderAnnotationUnavailable issue = new ReaderAnnotationUnavailable(
                "annotation", ReaderAnnotationUnavailableReason.UNRESOLVED, source, null);

        String label = invokeLabel(controller, issue);

        assertThat(hasUnpairedSurrogate(label)).isFalse();
    }

    private static String invokeLabel(NewReaderWorkspaceController controller, ReaderAnnotationUnavailable issue) throws Exception {
        Method method = NewReaderWorkspaceController.class.getDeclaredMethod("annotationIssueLabel", ReaderAnnotationUnavailable.class);
        method.setAccessible(true);
        return (String) method.invoke(controller, issue);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = NewReaderWorkspaceController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }
}
