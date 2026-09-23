package com.myhomelibcorp.ui.util;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class UiExceptionSupportTest {
    @Test
    void userMessageNeverLeaksRawTechnicalMessage() {
        String raw = "SQLITE_BUSY at /private/library.db?token=secret";
        String message = UiExceptionSupport.message(new SQLException(raw));
        assertThat(message).isNotBlank().doesNotContain(raw).doesNotContain("secret");
        assertThat(UiExceptionSupport.technicalMessage(new SQLException(raw))).contains("SQLITE_BUSY");
    }

    @Test
    void classifiesCommonFailureKindsThroughAsyncWrappers() {
        assertThat(UiExceptionSupport.message(new CompletionException(new ConnectException("refused"))))
                .isEqualTo(com.myhomelibcorp.ui.service.UiText.text("common.error.network"));
        assertThat(UiExceptionSupport.message(new TimeoutException("slow")))
                .isEqualTo(com.myhomelibcorp.ui.service.UiText.text("common.error.timeout"));
        assertThat(UiExceptionSupport.message(new AccessDeniedException("/secret")))
                .isEqualTo(com.myhomelibcorp.ui.service.UiText.text("common.error.access_denied"));
        assertThat(UiExceptionSupport.message(new NoSuchFileException("/missing")))
                .isEqualTo(com.myhomelibcorp.ui.service.UiText.text("common.error.file_not_found"));
        assertThat(UiExceptionSupport.message(new IllegalArgumentException("bad value")))
                .isEqualTo(com.myhomelibcorp.ui.service.UiText.text("common.error.invalid_data"));
    }
}
