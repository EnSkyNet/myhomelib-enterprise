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
                .bookId(BOOK_ID).anchorId("0:2:0:2").readingTimeSeconds(77).build()));

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
        assertThat(doc.message()).contains("не підтримується");
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
