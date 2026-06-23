package com.myhomelibcorp.application.importing;

import com.myhomelibcorp.application.importer.api.ImporterApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
public class ImportDirectoryUseCase {

    private final ImporterApplicationService importerService;

    public int importDirectory(Path directory, DoubleConsumer progressConsumer, AtomicBoolean cancelFlag) {
        return importerService.importDirectory(directory, progressConsumer, cancelFlag);
    }
}