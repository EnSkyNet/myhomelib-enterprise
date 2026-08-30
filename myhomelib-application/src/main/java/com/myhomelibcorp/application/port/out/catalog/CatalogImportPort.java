package com.myhomelibcorp.application.port.out.catalog;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;

import java.nio.file.Path;

/**
 * Source-neutral streaming catalog import boundary. Implementations must not buffer the full dataset.
 */
public interface CatalogImportPort {
    /** True only when the source can be confidently handled by the neutral catalog pipeline. */
    boolean supports(Path source);

    /** Imports a catalog using full-snapshot or delta semantics from the supplied context. */
    ImportResult importCatalog(ImportContext context);
}
