package com.myhomelibcorp.application.port.out.exporter;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.SeriesOmnibusPlanner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** A readable volume, not a container of independent book files. */
public interface SeriesOmnibusPort {
    void assemble(SeriesOmnibusPlanner.SeriesPlan plan, Path stagedOutput,
                  ExportRequest.ExportFormat format, BooleanSupplier cancelled) throws IOException;
}
