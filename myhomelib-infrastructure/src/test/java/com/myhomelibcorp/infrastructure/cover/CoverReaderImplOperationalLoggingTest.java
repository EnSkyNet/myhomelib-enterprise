package com.myhomelibcorp.infrastructure.cover;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverLocator;
import com.myhomelibcorp.infrastructure.image.EpubCoverParser;
import com.myhomelibcorp.infrastructure.image.FallbackCoverRenderer;
import com.myhomelibcorp.infrastructure.image.Fb2CoverParser;
import com.myhomelibcorp.infrastructure.image.MobiCoverParser;
import com.myhomelibcorp.infrastructure.image.PdfCoverParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoverReaderImplOperationalLoggingTest {

    @TempDir Path tempDir;

    @Test
    void emitsStartAndDoneForSuccessfulCoverExtraction() throws Exception {
        Path fb2 = Files.writeString(tempDir.resolve("book.fb2"), "<FictionBook/>");
        CoverLocator locator = mock(CoverLocator.class);
        when(locator.locateCoverFile(any())).thenReturn(Optional.of(fb2));
        Fb2CoverParser parser = mock(Fb2CoverParser.class);
        when(parser.parseToBytes(any(java.io.InputStream.class))).thenReturn(new byte[]{1, 2, 3});

        try (CapturedLogs logs = capture()) {
            assertThat(reader(locator, parser).readCover(book())).containsExactly(1, 2, 3);
            assertThat(logs.messages()).anyMatch(message -> message.startsWith("COVER_START id=book-1"));
            assertThat(logs.messages()).anyMatch(message -> message.startsWith("COVER_DONE id=book-1") && message.contains("bytes=3"));
        }
    }

    @Test
    void emitsStartAndNoneWhenLocalSourceCannotBeResolved() {
        CoverLocator locator = mock(CoverLocator.class);
        when(locator.locateCoverFile(any())).thenReturn(Optional.empty());

        try (CapturedLogs logs = capture()) {
            assertThat(reader(locator, mock(Fb2CoverParser.class)).readCover(book())).isNull();
            assertThat(logs.messages()).anyMatch(message -> message.startsWith("COVER_START id=book-1"));
            assertThat(logs.messages()).anyMatch(message -> message.startsWith("COVER_NONE id=book-1")
                    && message.contains("reason=source-unavailable"));
        }
    }

    @Test
    void interruptedCoverWorkIsLoggedAsCancellationNotFailure() {
        CoverLocator locator = mock(CoverLocator.class);
        try (CapturedLogs logs = capture()) {
            Thread.currentThread().interrupt();
            try {
                assertThat(reader(locator, mock(Fb2CoverParser.class)).readCover(book())).isNull();
                assertThat(logs.messages()).anyMatch(message -> message.startsWith("COVER_CANCEL id=book-1"));
                assertThat(logs.messages()).noneMatch(message -> message.startsWith("COVER_FAIL id=book-1"));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void emitsFailWithBookIdentityWhenCoverPipelineThrows() {
        CoverLocator locator = mock(CoverLocator.class);
        when(locator.locateCoverFile(any())).thenThrow(new IllegalStateException("broken locator"));

        try (CapturedLogs logs = capture()) {
            assertThat(reader(locator, mock(Fb2CoverParser.class)).readCover(book())).isNull();
            assertThat(logs.messages()).anyMatch(message -> message.startsWith("COVER_FAIL id=book-1")
                    && message.contains("broken locator"));
        }
    }

    private CoverReaderImpl reader(CoverLocator locator, Fb2CoverParser parser) {
        return new CoverReaderImpl(locator, mock(ZipArchiveReader.class), parser,
                mock(EpubCoverParser.class), mock(MobiCoverParser.class), mock(PdfCoverParser.class),
                mock(FallbackCoverRenderer.class));
    }

    private static BookDto book() {
        return BookDto.builder().id("book-1").title("Title").fileName("book.fb2").build();
    }

    private static CapturedLogs capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(CoverReaderImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return new CapturedLogs(logger, appender);
    }

    private record CapturedLogs(Logger logger, ListAppender<ILoggingEvent> appender) implements AutoCloseable {
        java.util.List<String> messages() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
        @Override public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
