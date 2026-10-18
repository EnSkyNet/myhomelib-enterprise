package com.myhomelibcorp.application.webreader;

import com.myhomelibcorp.application.content.*;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebReaderServiceTest {
    private static final String BOOK_ID = "11111111-1111-1111-1111-111111111111";
    @TempDir Path temp;

    @Test void opensFb2AtSharedDesktopProgressAndPreservesChapterOffsets() throws Exception {
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        Path file = Files.writeString(temp.resolve("book.fb2"), "stub");
        when(load.execute(any())).thenReturn(Optional.of(book("book.fb2")));
        when(resolve.execute(any(), anySet())).thenReturn(new ResolvedBookContent(file, false));
        when(extraction.extract(any(), any())).thenReturn(ContentExtractionResult.success("fb2-content", content()));
        when(progress.findByBookId(BOOK_ID)).thenReturn(Optional.of(ReadingProgressDto.builder()
                .bookId(BOOK_ID).anchorId("1:15:0:3").chapterId("c2").percent(75).updatedAt(LocalDateTime.now()).build()));

        WebReaderDocument doc = new WebReaderService(load, resolve, extraction, progress).open(BOOK_ID, -1).orElseThrow();

        assertThat(doc.supported()).isTrue();
        assertThat(doc.format()).isEqualTo("fb2");
        assertThat(doc.chapters()).hasSize(2);
        assertThat(doc.selectedChapter()).isEqualTo(1);
        assertThat(doc.resumeChapter()).isEqualTo(1);
        assertThat(doc.resumeOffset()).isEqualTo(15);
        assertThat(doc.currentChapter().title()).isEqualTo("Second");
    }

    @Test void savesProgressInDesktopCompatibleReaderPositionFormat() throws Exception {
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        Path file = Files.writeString(temp.resolve("book.epub"), "stub");
        when(load.execute(any())).thenReturn(Optional.of(book("book.epub")));
        when(resolve.execute(any(), anySet())).thenReturn(new ResolvedBookContent(file, false));
        when(extraction.extract(any(), any())).thenReturn(ContentExtractionResult.success("epub-content", content()));
        when(progress.findByBookId(BOOK_ID)).thenReturn(Optional.of(ReadingProgressDto.builder()
                .bookId(BOOK_ID).revision(0L).anchorId("0:2:0:2").readingTimeSeconds(77).build()));

        ReadingProgressDto saved = new WebReaderService(load, resolve, extraction, progress).saveProgress(BOOK_ID, 1, 17);

        assertThat(saved.getAnchorId()).isEqualTo("1:17:0:6");
        assertThat(saved.getChapterId()).isEqualTo("c2");
        assertThat(saved.getChapterTitle()).isEqualTo("Second");
        assertThat(saved.getReadingTimeSeconds()).isEqualTo(77);
        assertThat(saved.getPercent()).isCloseTo(80.9524, org.assertj.core.data.Offset.offset(0.001));
        ArgumentCaptor<ReadingProgressDto> captor = ArgumentCaptor.forClass(ReadingProgressDto.class);
        verify(progress).save(captor.capture());
        assertThat(captor.getValue().getAnchorId()).isEqualTo("1:17:0:6");
    }


    @Test void duplicateQueuedProgressReplayIsIdempotentAndDoesNotRewriteCanonicalState() throws Exception {
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        ReadingProgressDto existing = ReadingProgressDto.builder()
                .bookId(BOOK_ID).anchorId("1:15:0:4").chapterId("c2").percent(75)
                .updatedAt(LocalDateTime.now().minusMinutes(1)).lastDevice("web").build();
        when(progress.findByBookId(BOOK_ID)).thenReturn(Optional.of(existing));

        WebProgressSaveResult result = new WebReaderService(load, resolve, extraction, progress).saveProgress(
                BOOK_ID, new WebProgressUpdate(1, 15, Instant.now(), 12));

        assertThat(result.accepted()).isTrue();
        assertThat(result.canonical()).isSameAs(existing);
        verify(progress, never()).save(any());
        verifyNoInteractions(load, resolve, extraction);
    }

    @Test void staleQueuedProgressCannotOverwriteNewerCanonicalServerProgress() throws Exception {
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        ReadingProgressDto existing = ReadingProgressDto.builder()
                .bookId(BOOK_ID).anchorId("1:18:0:7").chapterId("c2").percent(90)
                .updatedAt(LocalDateTime.now()).lastDevice("desktop").build();
        when(progress.findByBookId(BOOK_ID)).thenReturn(Optional.of(existing));

        WebProgressSaveResult result = new WebReaderService(load, resolve, extraction, progress).saveProgress(
                BOOK_ID, new WebProgressUpdate(0, 5, Instant.now().minusSeconds(600), 99));

        assertThat(result.accepted()).isFalse();
        assertThat(result.canonical()).isSameAs(existing);
        verify(progress, never()).save(any());
        verifyNoInteractions(load, resolve, extraction);
    }

    @Test void browserSaveUsesObservedRevisionEvenIfDesktopUpdatesDuringExtraction() throws Exception {
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        Path file = Files.writeString(temp.resolve("race.fb2"), "stub");
        when(load.execute(any())).thenReturn(Optional.of(book("race.fb2")));
        when(resolve.execute(any(), anySet())).thenReturn(new ResolvedBookContent(file, false));
        when(extraction.extract(any(), any())).thenReturn(ContentExtractionResult.success("fb2-content", content()));
        ReadingProgressDto before = ReadingProgressDto.builder().bookId(BOOK_ID)
                .revision(4L).anchorId("0:2:0:2").chapterId("c1").percent(10)
                .updatedAt(LocalDateTime.now().minusMinutes(5)).build();
        ReadingProgressDto desktop = ReadingProgressDto.builder().bookId(BOOK_ID)
                .revision(5L).anchorId("1:19:0:8").chapterId("c2").percent(95)
                .updatedAt(LocalDateTime.now()).build();
        when(progress.findByBookId(BOOK_ID))
                .thenReturn(Optional.of(before), Optional.of(desktop), Optional.of(desktop), Optional.of(desktop));
        doAnswer(invocation -> {
            ReadingProgressDto attempt = invocation.getArgument(0);
            if (!Long.valueOf(5L).equals(attempt.getRevision()))
                throw new com.myhomelibcorp.application.exception.ReadingProgressConflictException("Stale CAS");
            return null;
        }).when(progress).save(any());

        WebProgressSaveResult result = new WebReaderService(load, resolve, extraction, progress)
                .saveProgress(BOOK_ID, new WebProgressUpdate(0, 5, Instant.now().minusSeconds(30), 123));

        assertThat(result.accepted()).isFalse();
        assertThat(result.canonical()).isSameAs(desktop);
        ArgumentCaptor<ReadingProgressDto> attempted = ArgumentCaptor.forClass(ReadingProgressDto.class);
        verify(progress).save(attempted.capture());
        assertThat(attempted.getValue().getRevision()).isEqualTo(4L);
    }

    @Test void directWebSaveUsesCompareAndSwapRevisionInsteadOfBlindLegacyUpsert() throws Exception {
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        Path file = Files.writeString(temp.resolve("direct.fb2"), "stub");
        when(load.execute(any())).thenReturn(Optional.of(book("direct.fb2")));
        when(resolve.execute(any(), anySet())).thenReturn(new ResolvedBookContent(file, false));
        when(extraction.extract(any(), any())).thenReturn(ContentExtractionResult.success("fb2-content", content()));
        when(progress.findByBookId(BOOK_ID)).thenReturn(Optional.of(ReadingProgressDto.builder()
                .bookId(BOOK_ID).revision(8L).anchorId("0:2:0:2").build()));

        new WebReaderService(load, resolve, extraction, progress).saveProgress(BOOK_ID, 0, 6);

        ArgumentCaptor<ReadingProgressDto> attempted = ArgumentCaptor.forClass(ReadingProgressDto.class);
        verify(progress).save(attempted.capture());
        assertThat(attempted.getValue().getRevision()).isEqualTo(8L);
    }

    @Test void unsupportedFormatOffersTypedFallbackWithoutExtraction() throws Exception {
        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        when(load.execute(any())).thenReturn(Optional.of(book("book.pdf")));
        when(progress.findByBookId(BOOK_ID)).thenReturn(Optional.empty());

        WebReaderDocument doc = new WebReaderService(load, resolve, extraction, progress).open(BOOK_ID, -1).orElseThrow();

        assertThat(doc.supported()).isFalse();
        assertThat(doc.format()).isEqualTo("pdf");
        assertThat(doc.messageKey()).isEqualTo("web.reader.unsupported_format");
        verifyNoInteractions(resolve, extraction);
    }

    private static BookDto book(String fileName) {
        return BookDto.builder().id(BOOK_ID).title("Book").fileName(fileName).folder("").collectionRoot("/").local(true).build();
    }

    private static ExtractedContent content() {
        return new ExtractedContent(BOOK_ID, "fb2", "0123456789\nabcdefghij", List.of(
                new ExtractedChapter("c1", "First", 0, 10, "0123456789", List.of()),
                new ExtractedChapter("c2", "Second", 11, 21, "abcdefghij", List.of())
        ));
    }
}
