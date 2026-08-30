package com.myhomelibcorp.application.port.out.download;

import com.myhomelibcorp.domain.model.collection.Collection;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Network access for remote catalog updates. */
public interface RemoteCatalogDownloadPort {

    /**
     * Resolves a server/base URL into the packages required to advance {@code currentVersion}.
     * Implementations must report both aggregate and detailed progress; no default overload may
     * silently drop detailed telemetry.
     */
    RemoteCatalogUpdatePlan downloadUpdates(
            Collection collection,
            String source,
            String currentVersion,
            AtomicBoolean cancel,
            DoubleConsumer progress,
            Consumer<RemoteDownloadProgress> detailedProgress) throws Exception;
}
