package com.myhomelibcorp.application.kobo;

import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.book.BookArtifactState;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KoboSyncServiceTest {
    @Test
    void projectsStableBookIdentityAndArtifactFingerprintWithoutTitleMatching() {
        BookQueryRepository books = mock(BookQueryRepository.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        KoboSyncPort port = mock(KoboSyncPort.class);
        Book first = book("00000000-0000-0000-0000-000000000001", "Same title", "fp-a");
        Book second = book("00000000-0000-0000-0000-000000000002", "Same title", "fp-b");
        when(books.streamAll()).thenReturn(Stream.of(first, second));
        AtomicReference<List<KoboBookProjection>> captured = new AtomicReference<>();
        when(port.reconcileAndLoad(any(), any(), anyInt())).thenAnswer(invocation -> {
            Stream<KoboBookProjection> stream = invocation.getArgument(0);
            captured.set(stream.toList());
            return new KoboSyncBatch(List.of(), KoboSyncCursor.initial(), false);
        });

        new KoboSyncService(books, progress, port).synchronize(KoboSyncCursor.initial(), 50);

        assertThat(captured.get()).extracting(KoboBookProjection::bookId)
                .containsExactly(first.getId().asString(), second.getId().asString());
        assertThat(captured.get()).extracting(KoboBookProjection::artifactRevision)
                .containsExactly("fp-a", "fp-b");
        assertThat(captured.get().get(0).revision()).isNotEqualTo(captured.get().get(1).revision());
    }


    @Test
    void convertibleFb2IsProjectedLazilyAsStableKepubWithoutEagerConversion() throws Exception {
        BookQueryRepository books = mock(BookQueryRepository.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        KoboSyncPort port = mock(KoboSyncPort.class);
        ConvertBookUseCase conversions = mock(ConvertBookUseCase.class);
        BookArtifact source = BookArtifact.builder()
                .id("source-fb2").format("fb2")
                .file(new BookFile("book.fb2", "", "", 456, ""))
                .contentFingerprint("source-fingerprint")
                .local(true).state(BookArtifactState.AVAILABLE).build();
        Book fb2 = Book.builder().id(BookId.fromString("00000000-0000-0000-0000-000000000003"))
                .title("FB2 book").artifacts(List.of(source)).preferredArtifactId(source.getId()).local(true)
                .updateDate(LocalDateTime.parse("2026-10-01T10:00:00")).build();
        when(books.streamAll()).thenReturn(Stream.of(fb2));
        when(conversions.capabilityMatrix()).thenReturn(List.of(new ConvertBookUseCase.CapabilityView(
                "fbc", true, new BookConversionCapability(java.util.Set.of("fb2"), "kepub", ".kepub"))));
        AtomicReference<List<KoboBookProjection>> captured = new AtomicReference<>();
        when(port.reconcileAndLoad(any(), any(), anyInt())).thenAnswer(invocation -> {
            Stream<KoboBookProjection> stream = invocation.getArgument(0);
            captured.set(stream.toList());
            return new KoboSyncBatch(List.of(), KoboSyncCursor.initial(), false);
        });

        new KoboSyncService(books, progress, port, conversions).synchronize(KoboSyncCursor.initial(), 50);

        assertThat(captured.get()).singleElement().satisfies(projected -> {
            assertThat(projected.bookId()).isEqualTo(fb2.getId().asString());
            assertThat(projected.format()).isEqualTo("kepub");
            assertThat(projected.artifactId()).isEqualTo("virtual:c291cmNlLWZiMg:kepub");
            assertThat(projected.artifactRevision()).isNotBlank();
        });
        verify(conversions, never()).execute(any());
    }

    @Test
    void staleKoboProgressIsRejectedWithoutRepositoryMutation() {
        BookQueryRepository books = mock(BookQueryRepository.class);
        ReadingProgressRepository repository = mock(ReadingProgressRepository.class);
        KoboSyncPort port = mock(KoboSyncPort.class);
        ReadingProgressDto canonical = ReadingProgressDto.builder().bookId("book-1").percent(75)
                .anchorId("c7").updatedAt(LocalDateTime.parse("2026-10-01T10:00:00")).build();
        when(repository.findByBookId("book-1")).thenReturn(Optional.of(canonical));
        when(port.observeProgressVersion(eq("book-1"), anyString())).thenReturn(10L);
        KoboSyncService service = new KoboSyncService(books, repository, port);

        KoboProgressState result = service.updateProgress(new KoboProgressUpdate("book-1", 20, "Reading", "c2",
                Instant.parse("2030-01-01T00:00:00Z"), 9));

        assertThat(result.version()).isEqualTo(10);
        assertThat(result.percent()).isEqualTo(75);
        verify(repository, never()).save(any());
    }

    @Test
    void acceptedKoboProgressPersistsThroughSharedReadingRepository() {
        BookQueryRepository books = mock(BookQueryRepository.class);
        ReadingProgressRepository repository = mock(ReadingProgressRepository.class);
        KoboSyncPort port = mock(KoboSyncPort.class);
        ReadingProgressDto canonical = ReadingProgressDto.builder().bookId("book-1").percent(10)
                .anchorId("c1").updatedAt(LocalDateTime.parse("2026-10-01T10:00:00")).build();
        when(repository.findByBookId("book-1")).thenReturn(Optional.of(canonical));
        when(port.observeProgressVersion(eq("book-1"), anyString())).thenReturn(4L, 5L);
        KoboSyncService service = new KoboSyncService(books, repository, port);

        KoboProgressState result = service.updateProgress(new KoboProgressUpdate("book-1", 50, "Reading", "c5",
                Instant.parse("2026-10-01T11:00:00Z"), 4));

        verify(repository).save(argThat(saved -> saved.getPercent() == 50 && "kobo".equals(saved.getLastDevice())));
        assertThat(result.version()).isEqualTo(5);
        assertThat(result.percent()).isEqualTo(50);
    }


    @Test
    void malformedBookIdIsRejectedWithoutRepositoryLookup() {
        BookQueryRepository books = mock(BookQueryRepository.class);
        KoboSyncService service = new KoboSyncService(books, mock(ReadingProgressRepository.class), mock(KoboSyncPort.class));

        assertThat(service.book("not-a-uuid")).isEmpty();

        verifyNoInteractions(books);
    }

    @Test
    void repositoryFailureWhileLoadingBookIsNotMisreportedAsUnknownBook() {
        BookQueryRepository books = mock(BookQueryRepository.class);
        ReadingProgressRepository progress = mock(ReadingProgressRepository.class);
        KoboSyncPort port = mock(KoboSyncPort.class);
        String id = "00000000-0000-0000-0000-000000000004";
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(books.findById(BookId.fromString(id))).thenThrow(failure);
        KoboSyncService service = new KoboSyncService(books, progress, port);

        assertThatThrownBy(() -> service.book(id)).isSameAs(failure);
    }

    @Test
    void credentialToStringRedactsSecret() {
        KoboCredentialIssue issue = new KoboCredentialIssue("device-1", "super-secret-token");
        assertThat(issue.toString()).contains("device-1", "<redacted>").doesNotContain("super-secret-token");
    }

    private static Book book(String id, String title, String fingerprint) {
        BookArtifact artifact = BookArtifact.builder()
                .id("artifact-" + id)
                .format("epub")
                .file(new BookFile(title + ".epub", "", "", 123, ""))
                .contentFingerprint(fingerprint)
                .local(true).state(BookArtifactState.AVAILABLE)
                .build();
        return Book.builder().id(BookId.fromString(id)).title(title).artifacts(List.of(artifact))
                .preferredArtifactId(artifact.getId()).local(true)
                .updateDate(LocalDateTime.parse("2026-10-01T10:00:00")).build();
    }
}
