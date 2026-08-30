package com.myhomelibcorp.application.imports.saver;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.Cover;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;

import java.util.ArrayList;

/**
 * Import/update merge policy that keeps the existing stable BookId and user-owned state.
 * Catalogue metadata may be refreshed, but rating, reading progress, review, creation/deletion
 * state and an already-local physical copy must never be reset by a newly parsed catalogue row.
 */
public final class ImportBookMergePolicy {
    private ImportBookMergePolicy() { }

    public static Book mergePreservingUserState(Book existing, Book incoming) {
        if (existing == null) return incoming;
        if (incoming == null) return existing;

        BookMetadata im = incoming.getMetadata();
        BookMetadata em = existing.getMetadata();
        BookMetadata mergedMetadata = BookMetadata.builder()
                .annotation(preferIncoming(text(im, Field.ANNOTATION), text(em, Field.ANNOTATION)))
                .keywords(preferIncoming(text(im, Field.KEYWORDS), text(em, Field.KEYWORDS)))
                .language(preferLanguage(im != null ? im.getLanguage() : null, existing.getLanguage()))
                .isbn(im != null && im.getIsbn() != null ? im.getIsbn() : existing.getIsbn())
                .review(existing.getReview())
                .year(im != null && im.getYear() != null ? im.getYear() : existing.getYear())
                .publisher(preferIncoming(im != null ? im.getPublisher() : null, existing.getPublisher()))
                .libId(preferIncoming(im != null ? im.getLibId() : null, existing.getLibId()))
                .libraryRate(im != null ? im.getLibraryRate() : existing.getLibraryRate())
                .translators(preferIncoming(im != null ? im.getTranslators() : null, existing.getTranslators()))
                .city(preferIncoming(im != null ? im.getCity() : null, existing.getCity()))
                .sourceUrl(preferIncoming(im != null ? im.getSourceUrl() : null, existing.getSourceUrl()))
                .rate(existing.getRate())
                .progress(existing.getProgress())
                .build();

        boolean preserveLocalCopy = existing.isLocal();
        BookFile effectiveFile = preserveLocalCopy ? existing.getFile() : incoming.getFile();
        boolean effectiveLocal = preserveLocalCopy || incoming.isLocal();
        Cover effectiveCover = incoming.getCover() != null && !incoming.getCover().isEmpty()
                ? incoming.getCover() : existing.getCover();

        return Book.builder()
                .id(existing.getId())
                .title(preferIncoming(incoming.getTitle(), existing.getTitle()))
                .authors(incoming.getAuthors() == null || incoming.getAuthors().isEmpty()
                        ? new ArrayList<>(existing.getAuthors()) : new ArrayList<>(incoming.getAuthors()))
                .genres(incoming.getGenres() == null
                        ? new ArrayList<>(existing.getGenres()) : new ArrayList<>(incoming.getGenres()))
                .series(incoming.getSeries())
                .sequenceNumber(incoming.getSequenceNumber())
                .metadata(mergedMetadata)
                .file(effectiveFile)
                .cover(effectiveCover)
                .updateDate(incoming.getUpdateDate())
                .createdAt(existing.getCreatedAt())
                .deleted(existing.isDeleted())
                .local(effectiveLocal)
                .build();
    }

    private static LanguageCode preferLanguage(LanguageCode incoming, LanguageCode existing) {
        if (incoming == null || "und".equalsIgnoreCase(incoming.toString())) return existing;
        return incoming;
    }

    private static String preferIncoming(String incoming, String existing) {
        return incoming == null || incoming.isBlank() ? (existing == null ? "" : existing) : incoming;
    }

    private static String text(BookMetadata metadata, Field field) {
        if (metadata == null) return null;
        return switch (field) {
            case ANNOTATION -> metadata.getAnnotation();
            case KEYWORDS -> metadata.getKeywords();
        };
    }

    private enum Field { ANNOTATION, KEYWORDS }
}
