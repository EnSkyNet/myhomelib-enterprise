package com.myhomelibcorp.ui.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.dto.GenreDto;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class BookViewModelMapper {

    private final BookSelectionService bookSelectionService;
    private final LocalizationService localizationService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public BookViewModel toViewModel(BookDto dto) {
        if (dto == null) return null;
        BookViewModel vm = new BookViewModel();
        vm.setId(dto.getId());
        vm.setTitle(dto.getTitle());
        vm.setAuthorsText(dto.getAuthorsText());
        vm.setSeries(dto.getSeries());
        vm.setGenresText(localizedGenres(dto.getGenreItems(), dto.getGenresText()));
        vm.setSequenceNumber(dto.getSequenceNumber() != null ? dto.getSequenceNumber() : 0);
        vm.setYear(dto.getYear());
        vm.setLanguage(dto.getLanguage());
        vm.setFileName(dto.getFileName());
        vm.setFolder(dto.getFolder());
        vm.setArchiveEntry(dto.getArchiveEntry());
        vm.setFileSize(dto.getFileSize());
        vm.setKeywords(dto.getKeywords());
        vm.setAnnotation(dto.getAnnotation());
        vm.setRate(dto.getRate());
        vm.setProgress(dto.getProgress());
        vm.setUpdateDate(dto.getUpdateDate());
        vm.setDeleted(dto.isDeleted());
        vm.setLocal(dto.isLocal());
        vm.setMissingSince(dto.getMissingSince());
        vm.setCollectionRoot(dto.getCollectionRoot());
        vm.setReview(dto.getReview());
        vm.setCreatedAt(dto.getCreatedAt());
        vm.setCover(null);
        bookSelectionService.bind(vm);
        return vm;
    }

    public BookViewModel toViewModel(BookListItem item) {
        if (item == null) return null;
        BookViewModel vm = new BookViewModel();
        vm.setId(item.getId());
        vm.setTitle(item.getTitle());
        vm.setAuthorsText(item.getAuthorsText());
        vm.setSeries(item.getSeries());
        vm.setGenresText(localizedGenres(item.getGenreItems(), item.getGenresText()));
        vm.setYear(item.getYear());
        vm.setRate(item.getRate());
        vm.setProgress(item.getProgress());
        vm.setFileSize(item.getFileSize());
        vm.setLocal(item.isLocal());
        vm.setMissingSince(item.getMissingSince());
        vm.setFileName(item.getFileName());
        vm.setFolder(item.getFolder());
        vm.setArchiveEntry(item.getArchiveEntry());
        vm.setCollectionRoot(item.getCollectionRoot());
        vm.setAnnotation(item.getAnnotation());
        vm.setLanguage(item.getLanguage());

        if (item.getCreatedAt() != null && !item.getCreatedAt().isEmpty()) {
            try {
                vm.setCreatedAt(LocalDateTime.parse(item.getCreatedAt(), DATE_FORMATTER));
            } catch (Exception ignored) {}
        }
        if (item.getUpdateDate() != null && !item.getUpdateDate().isEmpty()) {
            try {
                vm.setUpdateDate(LocalDateTime.parse(item.getUpdateDate(), DATE_FORMATTER));
            } catch (Exception ignored) {}
        }

        vm.setSequenceNumber(item.getSequenceNumber() != null ? item.getSequenceNumber() : 0);
        vm.setKeywords("");
        vm.setReview("");
        vm.setDeleted(false);
        vm.setCover(null);
        bookSelectionService.bind(vm);
        return vm;
    }

    private String localizedGenres(java.util.List<GenreDto> genres, String fallback) {
        // Never use raw genresText as a UI fallback: legacy DTO text may contain
        // internal identifiers or top-level taxonomy groups.
        if (genres == null || genres.isEmpty()) return "";
        java.util.List<String> siblingCodes = genres.stream()
                .filter(java.util.Objects::nonNull)
                .map(GenreDto::getCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        return genres.stream()
                .filter(java.util.Objects::nonNull)
                .filter(genre -> localizationService.shouldDisplayGenre(genre.getCode(), siblingCodes))
                .map(genre -> localizationService.genreName(genre.getCode(),
                        genre.getName() == null || genre.getName().isBlank() ? genre.getCode() : genre.getName()))
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public BookDto toDto(BookViewModel vm) {
        if (vm == null) return null;
        BookDto dto = new BookDto();
        dto.setId(vm.getId());
        dto.setTitle(vm.getTitle());
        dto.setAuthorsText(vm.getAuthorsText());
        dto.setSeries(vm.getSeries());
        dto.setGenresText(vm.getGenresText());
        dto.setSequenceNumber(vm.getSequenceNumber());
        dto.setYear(vm.getYear() > 0 ? vm.getYear() : null);
        dto.setLanguage(vm.getLanguage());
        dto.setFileName(vm.getFileName());
        dto.setFolder(vm.getFolder());
        dto.setArchiveEntry(vm.getArchiveEntry());
        dto.setFileSize(vm.getFileSize());
        dto.setKeywords(vm.getKeywords());
        dto.setAnnotation(vm.getAnnotation());
        dto.setRate(vm.getRate());
        dto.setProgress(vm.getProgress());
        dto.setUpdateDate(vm.getUpdateDate());
        dto.setDeleted(vm.isDeleted());
        dto.setLocal(vm.isLocal());
        dto.setMissingSince(vm.getMissingSince());
        dto.setCollectionRoot(vm.getCollectionRoot());
        dto.setReview(vm.getReview());
        dto.setCreatedAt(vm.getCreatedAt());
        return dto;
    }
}