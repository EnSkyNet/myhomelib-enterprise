package com.myhomelibcorp.application.recommendation;

import com.myhomelibcorp.domain.model.book.Book;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** Deterministic metadata-only similarity policy. No behavioral profile, network call, embedding, or AI. */
@Component
public final class BookSimilarityPolicy {
    public SimilarBookResult score(Book source, Book candidate) {
        Objects.requireNonNull(source); Objects.requireNonNull(candidate);
        if (source.getId().equals(candidate.getId()) || candidate.isDeleted()) return empty(candidate);
        List<BookSimilarityEvidence> reasons = new ArrayList<>();
        double score = 0;
        if (sameNonBlank(source.getSeries(), candidate.getSeries())) {
            score += 40; reasons.add(new BookSimilarityEvidence("series", candidate.getSeries(), 40));
        }
        Set<String> authorsA = source.getAuthors().stream().map(a -> a.getId().asString()).collect(Collectors.toSet());
        Set<String> authorsB = candidate.getAuthors().stream().map(a -> a.getId().asString()).collect(Collectors.toSet());
        double authors = overlap(authorsA, authorsB) * 25;
        if (authors > 0) { score += authors; reasons.add(new BookSimilarityEvidence("authors", "shared authors", authors)); }
        Set<String> genresA = source.getGenres().stream().map(g -> g.getId().asString()).collect(Collectors.toSet());
        Set<String> genresB = candidate.getGenres().stream().map(g -> g.getId().asString()).collect(Collectors.toSet());
        double genres = jaccard(genresA, genresB) * 20;
        if (genres > 0) { score += genres; reasons.add(new BookSimilarityEvidence("genres", "genre overlap", genres)); }
        Set<String> keywordsA = tokens(source.getKeywords()); Set<String> keywordsB = tokens(candidate.getKeywords());
        double keywords = jaccard(keywordsA, keywordsB) * 10;
        if (keywords > 0) { score += keywords; reasons.add(new BookSimilarityEvidence("keywords", "keyword overlap", keywords)); }
        if (!"und".equals(source.getLanguage().value()) && source.getLanguage().equals(candidate.getLanguage())) {
            score += 3; reasons.add(new BookSimilarityEvidence("language", source.getLanguage().value(), 3));
        }
        int a=source.getRate(), b=candidate.getRate();
        if (a > 0 && b > 0) {
            double rating = Math.max(0, 2.0 - Math.abs(a-b) * 0.4);
            if (rating > 0) { score += rating; reasons.add(new BookSimilarityEvidence("rating", "similar rating", rating)); }
        }
        reasons.sort(Comparator.comparingDouble(BookSimilarityEvidence::contribution).reversed().thenComparing(BookSimilarityEvidence::kind));
        return new SimilarBookResult(candidate.getId().asString(), candidate.getTitle(), round(score), reasons);
    }

    private static SimilarBookResult empty(Book candidate) { return new SimilarBookResult(candidate.getId().asString(), candidate.getTitle(), 0, List.of()); }
    private static boolean sameNonBlank(String a,String b){return a!=null&&!a.isBlank()&&b!=null&&!b.isBlank()&&a.trim().equalsIgnoreCase(b.trim());}
    private static double overlap(Set<String>a,Set<String>b){if(a.isEmpty()||b.isEmpty())return 0; long common=a.stream().filter(b::contains).count(); return common/(double)Math.min(a.size(),b.size());}
    private static double jaccard(Set<String>a,Set<String>b){if(a.isEmpty()||b.isEmpty())return 0; long common=a.stream().filter(b::contains).count(); return common/(double)(a.size()+b.size()-common);}
    private static Set<String> tokens(String value){if(value==null||value.isBlank())return Set.of(); return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) .filter(s->!s.isBlank()).collect(Collectors.toCollection(TreeSet::new));}
    private static double round(double value){return Math.round(value*1000.0)/1000.0;}
}
