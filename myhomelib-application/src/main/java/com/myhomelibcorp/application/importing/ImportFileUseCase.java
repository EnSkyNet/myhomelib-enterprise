package com.myhomelibcorp.application.importing;

import com.myhomelibcorp.application.importer.api.ImporterApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class ImportFileUseCase {

    private final ImporterApplicationService importerService;

    public int importFile(Path file) {
        return importerService.importBooks(file);
    }
}