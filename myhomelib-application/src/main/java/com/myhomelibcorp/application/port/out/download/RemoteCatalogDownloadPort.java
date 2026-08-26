package com.myhomelibcorp.application.port.out.download;

import com.myhomelibcorp.domain.model.collection.Collection;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

/** Network access for remote catalog updates. */
public interface RemoteCatalogDownloadPort {

    /** Downloads one explicit catalog URL. Kept for direct-file compatibility. */
    Path download(Collection collection, String url, AtomicBoolean cancel, DoubleConsumer progress) throws Exception;

    /**
     * Resolves a server/base URL into the packages required to advance {@code currentVersion}.
     * Implementations may return a full snapshot followed by one or more incremental packages.
     */
    RemoteCatalogUpdatePlan downloadUpdates(
            Collection collection,
            String source,
            String currentVersion,
            AtomicBoolean cancel,
            DoubleConsumer progress) throws Exception;
}
