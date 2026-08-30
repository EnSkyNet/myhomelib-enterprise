package com.myhomelibcorp.application.catalog.importing;

import java.util.Iterator;

public interface CatalogReadSession extends Iterator<CatalogRecord>, AutoCloseable {
    CatalogDatasetInfo dataset();
    @Override void close();
}
