package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.application.metadata.MetadataSource;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApplyMetadataCandidateUseCaseTest {

    @Test
    void emptySelectionHasNoSideEffects() {
        Book current = book();
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(queries.findById(current.getId())).thenReturn(Optional.of(current));
        ApplyMetadataCandidateUseCase useCase = new ApplyMetadataCandidateUseCase(queries, mutations);

        Book result = useCase.execute(new ApplyMetadataCandidateUseCase.Request(current.getId(), candidate(), Set.of()));

        assertThat(result).isSameAs(current);
        verifyNoInteractions(mutations);
    }

    @Test
    void appliesOnlyExplicitlySelectedFieldsAndPreservesUserAndStorageState() {
        Book current = book();
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(queries.findById(current.getId())).thenReturn(Optional.of(current));
        ApplyMetadataCandidateUseCase useCase = new ApplyMetadataCandidateUseCase(queries, mutations);

        Book result = useCase.execute(new ApplyMetadataCandidateUseCase.Request(
                current.getId(), candidate(), Set.of(MetadataMergeField.TITLE, MetadataMergeField.ISBN,
                MetadataMergeField.PUBLISHER)));

        ArgumentCaptor<Book> saved = ArgumentCaptor.forClass(Book.class);
        verify(mutations).save(saved.capture());
        assertThat(result).isSameAs(saved.getValue());
        assertThat(result.getTitle()).isEqualTo("Remote title");
        assertThat(result.getIsbn()).isEqualTo(Isbn.of("9780306406157"));
        assertThat(result.getPublisher()).isEqualTo("Remote Publisher");
        assertThat(result.authorsText()).isEqualTo(current.authorsText());
        assertThat(result.getLanguage()).isEqualTo(current.getLanguage());
        assertThat(result.getYear()).isEqualTo(current.getYear());
        assertThat(result.getAnnotation()).isEqualTo(current.getAnnotation());
        assertThat(result.getKeywords()).isEqualTo("keep-keywords");
        assertThat(result.getReview()).isEqualTo("keep-review");
        assertThat(result.getRate()).isEqualTo(5);
        assertThat(result.getProgress()).isEqualTo(72);
        assertThat(result.getFile()).isSameAs(current.getFile());
        assertThat(result.getCover()).isSameAs(current.getCover());
        assertThat(result.getArtifacts()).containsExactlyElementsOf(current.getArtifacts());
    }

    @Test
    void selectedAuthorsAndLanguageUseCandidateValues() {
        Book current = book();
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(queries.findById(current.getId())).thenReturn(Optional.of(current));
        ApplyMetadataCandidateUseCase useCase = new ApplyMetadataCandidateUseCase(queries, mutations);

        Book result = useCase.execute(new ApplyMetadataCandidateUseCase.Request(
                current.getId(), candidate(), Set.of(MetadataMergeField.AUTHORS, MetadataMergeField.LANGUAGE,
                MetadataMergeField.YEAR, MetadataMergeField.ANNOTATION)));

        assertThat(result.authorsText()).isEqualTo("Remote Author");
        assertThat(result.getLanguage()).isEqualTo(LanguageCode.of("en"));
        assertThat(result.getYear()).isEqualTo(2024);
        assertThat(result.getAnnotation()).isEqualTo("remote annotation");
    }

    private static Book book() {
        return Book.builder().id(BookId.generate()).title("Local title")
                .authors(List.of(new Author("Local Author", "", "")))
                .metadata(BookMetadata.builder().annotation("local annotation").keywords("keep-keywords")
                        .language(LanguageCode.of("uk")).isbn(Isbn.of("9783161484100")).review("keep-review")
                        .year(2001).publisher("Local Publisher").libId("L1").libraryRate(4)
                        .translators("Translator").city("Kyiv").sourceUrl("local://source").rate(5).progress(72).build())
                .file(BookFile.empty()).cover(Cover.empty()).local(true).build();
    }

    private static MetadataCandidate candidate() {
        return new MetadataCandidate(
                new MetadataSource("provider-a", "Provider A", "r-1", "https://example.invalid/r-1"),
                0.93, "Remote title", List.of("Remote Author"), "9780306406157", 2024,
                "Remote Publisher", "en", "remote annotation", "https://example.invalid/cover.jpg");
    }
}
