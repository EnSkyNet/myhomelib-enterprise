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

    /**
     * Imports a catalog using full-snapshot or delta semantics from the supplied context.
     *
     * <p>{@code ImportResult.changes().complete()} is the only guarantee that the returned ID sets
     * are exact. In particular, full-snapshot implementations may intentionally return
     * {@code complete=false} to avoid retaining a catalog-sized change set in memory; callers must
     * not infer exact changed IDs from the imported-row count.</p>
     */
    ImportResult importCatalog(ImportContext context);
}
