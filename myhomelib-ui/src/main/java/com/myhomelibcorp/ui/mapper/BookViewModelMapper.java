package com.myhomelibcorp.ui.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import org.springframework.stereotype.Component;

@Component
public class BookViewModelMapper {

    public BookViewModel toViewModel(BookDto dto) {
        if (dto == null) return null;

        BookViewModel vm = new BookViewModel();
        vm.setId(dto.getId());
        vm.setTitle(dto.getTitle());
        vm.setAuthorsText(dto.getAuthorsText());
        vm.setSeries(dto.getSeries());
        vm.setGenresText(dto.getGenresText());
        vm.setSequenceNumber(dto.getSequenceNumber() != null ? dto.getSequenceNumber() : 0);
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
}