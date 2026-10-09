package com.myhomelibcorp.application.recommendation;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookSimilarityPolicyTest {
    private final Author sharedAuthor = new Author(AuthorId.fromString("00000000-0000-0000-0000-000000000010"),"A","","Author");
    private final Genre sharedGenre = new Genre("sf","Science Fiction");

    @Test
    void metadataScoreIsDeterministicExplainableAndExcludesSameBook() {
        Book source=book("00000000-0000-0000-0000-000000000001","Source","Saga",List.of(sharedAuthor),List.of(sharedGenre),"space future","en",5,false);
        Book candidate=book("00000000-0000-0000-0000-000000000002","Candidate","Saga",List.of(sharedAuthor),List.of(sharedGenre),"space opera","en",4,false);
        BookSimilarityPolicy policy=new BookSimilarityPolicy();
        var first=policy.score(source,candidate); var second=policy.score(source,candidate);
        assertThat(first).isEqualTo(second);
        assertThat(first.score()).isPositive();
        assertThat(first.reasons()).extracting(BookSimilarityEvidence::kind).contains("series","authors","genres","keywords","language","rating");
        assertThat(policy.score(source,source).score()).isZero();
    }

    @Test
    void emptyMetadataProducesNoRecommendation() {
        Book a=book("00000000-0000-0000-0000-000000000003","A",null,List.of(),List.of(),"","und",0,false);
        Book b=book("00000000-0000-0000-0000-000000000004","B",null,List.of(),List.of(),"","und",0,false);
        assertThat(new BookSimilarityPolicy().score(a,b).score()).isZero();
    }

    @Test
    void useCaseExcludesDeletedCandidates() {
        Book source=book("00000000-0000-0000-0000-000000000020","S","Saga",List.of(sharedAuthor),List.of(sharedGenre),"space","en",5,false);
        Book deleted=book("00000000-0000-0000-0000-000000000021","Deleted","Saga",List.of(sharedAuthor),List.of(sharedGenre),"space","en",5,true);
        Book live=book("00000000-0000-0000-0000-000000000022","Live","Saga",List.of(sharedAuthor),List.of(sharedGenre),"space","en",5,false);
        BookQueryRepository repo=mock(BookQueryRepository.class); when(repo.findById(source.getId())).thenReturn(Optional.of(source)); when(repo.streamSimilarityCandidates(source,10)).thenReturn(java.util.stream.Stream.of(deleted,live));
        assertThat(new FindSimilarBooksUseCase(repo,new BookSimilarityPolicy()).find(source.getId(),10))
                .extracting(SimilarBookResult::bookId).containsExactly(live.getId().toString());
    }

    @Test
    void useCaseStopsPromptlyWhenInteractiveLookupIsInterrupted() {
        Book source=book("00000000-0000-0000-0000-000000000030","S","Saga",List.of(sharedAuthor),List.of(sharedGenre),"space","en",5,false);
        List<Book> candidates=IntStream.range(0,100).mapToObj(i -> book(new UUID(1,i+100).toString(),"B"+i,"Saga",List.of(sharedAuthor),List.of(sharedGenre),"space","en",5,false)).toList();
        BookQueryRepository repo=mock(BookQueryRepository.class);
        when(repo.findById(source.getId())).thenReturn(Optional.of(source));
        when(repo.streamSimilarityCandidates(source,8)).thenReturn(candidates.stream().peek(candidate -> {
            if (candidate == candidates.get(5)) Thread.currentThread().interrupt();
        }));
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new FindSimilarBooksUseCase(repo,new BookSimilarityPolicy()).find(source.getId(),8))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void useCaseKeepsOnlyBoundedTopKAndDeterministicTieOrder() {
        Book source=book("00000000-0000-0000-0000-000000000005","S","Saga",List.of(sharedAuthor),List.of(sharedGenre),"space","en",5,false);
        List<Book> candidates=IntStream.range(0,2000).mapToObj(i -> book(new UUID(0,i+100).toString(),"B"+i,i%2==0?"Saga":"Other",List.of(sharedAuthor),List.of(sharedGenre),i%3==0?"space":"other","en",5,false)).toList();
        BookQueryRepository repo=mock(BookQueryRepository.class); when(repo.findById(source.getId())).thenReturn(Optional.of(source)); when(repo.streamSimilarityCandidates(source,7)).thenReturn(candidates.stream());
        List<SimilarBookResult> result=new FindSimilarBooksUseCase(repo,new BookSimilarityPolicy()).find(source.getId(),7);
        assertThat(result).hasSize(7);
        assertThat(result).isSortedAccordingTo(Comparator.comparingDouble(SimilarBookResult::score).reversed().thenComparing(SimilarBookResult::bookId));
    }

    private static Book book(String id,String title,String series,List<Author> authors,List<Genre> genres,String keywords,String language,int rate,boolean deleted){
        return Book.builder().id(BookId.fromString(id)).title(title).series(series).authors(authors).genres(genres).deleted(deleted)
                .metadata(BookMetadata.builder().keywords(keywords).language(LanguageCode.of(language)).rate(rate).build())
                .file(BookFile.empty()).build();
    }
}
