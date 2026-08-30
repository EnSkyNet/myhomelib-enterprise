package com.myhomelibcorp.ui.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class BookViewModelMapper {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public BookViewModel toViewModel(BookDto dto) {
        if (dto == null) return null;
        BookViewModel vm = new BookViewModel();
        vm.setId(dto.getId());
        vm.setTitle(dto.getTitle());
        vm.setAuthorsText(dto.getAuthorsText());
        vm.setSeries(dto.getSeries());
        vm.setGenresText(dto.getGenresText());
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
        vm.setCollectionRoot(dto.getCollectionRoot());
        vm.setReview(dto.getReview());
        vm.setCreatedAt(dto.getCreatedAt());
        vm.setCover(null);
        return vm;
    }

    public BookViewModel toViewModel(BookListItem item) {
        if (item == null) return null;
        BookViewModel vm = new BookViewModel();
        vm.setId(item.getId());
        vm.setTitle(item.getTitle());
        vm.setAuthorsText(item.getAuthorsText());
        vm.setSeries(item.getSeries());
        vm.setGenresText(item.getGenresText());
        vm.setYear(item.getYear());
        vm.setRate(item.getRate());
        vm.setProgress(item.getProgress());
        vm.setFileSize(item.getFileSize());
        vm.setLocal(item.isLocal());
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
        return vm;
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
        dto.setCollectionRoot(vm.getCollectionRoot());
        dto.setReview(vm.getReview());
        dto.setCreatedAt(vm.getCreatedAt());
        return dto;
    }
}