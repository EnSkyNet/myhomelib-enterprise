package com.myhomelibcorp.infrastructure.importer;

import com.myhomelibcorp.application.port.out.importer.FastImportService;
import com.myhomelibcorp.infrastructure.importengine.InpxImportPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
@RequiredArgsConstructor
@Slf4j
public class InpxFastImportService implements FastImportService {

    private final InpxImportPipeline pipeline;

    @Override
    public long importInpx(Path file, int batchSize, Path rootDirectory) {
        log.info("Fast import INPX: {} (root: {})", file, rootDirectory);
        return pipeline.importFile(file, batchSize, rootDirectory);
    }
}