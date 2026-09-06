package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EditBookUseCaseTest {
    @Test
    void editsUserFieldsAndPreservesNonEditableMetadataAndStorage() {
        BookId id = BookId.generate();
        Book current = Book.builder().id(id).title("Old")
                .authors(List.of(new Author("Old", "", "Author")))
                .genres(List.of(new Genre("fantasy", "Fantasy")))
                .series("Old series").sequenceNumber(1)
                .metadata(BookMetadata.builder().annotation("old annotation").keywords("old keywords")
                        .language(LanguageCode.of("uk")).isbn(Isbn.of("9783161484100")).review("old review")
                        .year(1999).publisher("old publisher").libId("LIB-42").libraryRate(7)
                        .translators("Translator").city("Kyiv").sourceUrl("https://example.invalid/source")
                        .rate(4).progress(61).build())
                .file(BookFile.empty()).local(true).build();
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(queries.findById(id)).thenReturn(Optional.of(current));
        EditBookUseCase useCase = new EditBookUseCase(queries, mutations);
        Author newAuthor = new Author("New", "", "Author");

        Book result = useCase.execute(new EditBookUseCase.Request(id, "  New title  ", List.of(newAuthor),
                " New series ", 3, LanguageCode.of("en"), 2026, "Publisher", "k1;k2", "annotation", "review"));

        ArgumentCaptor<Book> saved = ArgumentCaptor.forClass(Book.class);
        verify(mutations).save(saved.capture());
        assertThat(result).isSameAs(saved.getValue());
        assertThat(result.getTitle()).isEqualTo("New title");
        assertThat(result.getAuthors()).containsExactly(newAuthor);
        assertThat(result.getGenres()).containsExactlyElementsOf(current.getGenres());
        assertThat(result.getSeries()).isEqualTo("New series");
        assertThat(result.getSequenceNumber()).isEqualTo(3);
        assertThat(result.getLanguage()).isEqualTo(LanguageCode.of("en"));
        assertThat(result.getYear()).isEqualTo(2026);
        assertThat(result.getPublisher()).isEqualTo("Publisher");
        assertThat(result.getLibId()).isEqualTo("LIB-42");
        assertThat(result.getLibraryRate()).isEqualTo(7);
        assertThat(result.getTranslators()).isEqualTo("Translator");
        assertThat(result.getCity()).isEqualTo("Kyiv");
        assertThat(result.getSourceUrl()).isEqualTo("https://example.invalid/source");
        assertThat(result.getRate()).isEqualTo(4);
        assertThat(result.getProgress()).isEqualTo(61);
        assertThat(result.getFile()).isSameAs(current.getFile());
    }

    @Test
    void missingBookFailsBeforeAnyMutation() {
        BookId id = BookId.generate();
        BookQueryRepository queries = mock(BookQueryRepository.class);
        CommittedCatalogMutationService mutations = mock(CommittedCatalogMutationService.class);
        when(queries.findById(id)).thenReturn(Optional.empty());
        EditBookUseCase useCase = new EditBookUseCase(queries, mutations);
        assertThatThrownBy(() -> useCase.execute(new EditBookUseCase.Request(id, "Title", List.of(), null,
                null, LanguageCode.of("uk"), null, "", "", "", "")))
                .isInstanceOf(EditBookUseCase.BookNotFoundException.class);
        verifyNoInteractions(mutations);
    }
}
