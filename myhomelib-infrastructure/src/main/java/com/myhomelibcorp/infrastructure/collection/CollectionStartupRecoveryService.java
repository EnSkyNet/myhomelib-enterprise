package com.myhomelibcorp.infrastructure.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Public, idempotent startup boundary for filesystem crash recovery.
 *
 * <p>Recovery must run before Hikari opens the target SQLite file. The same service is invoked by
 * {@link CollectionManager} as a final safety net, while bootstrap calls it explicitly so startup
 * ordering and failure policy remain observable and independently testable.</p>
 */
@Component
@Slf4j
public class CollectionStartupRecoveryService {

    public void recoverBeforeOpen(Collection collection) {
        if (collection == null) throw new IllegalArgumentException("Collection is required for startup recovery");
        Path target = CollectionDatabasePathResolver.resolve(collection).toAbsolutePath().normalize();
        try {
            CollectionCrashRecovery.recoverBeforeOpen(collection, target);
            log.debug("Startup recovery completed for collection {} ({})", collection.getId(), target);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot recover collection before startup: " + collection.getId(), e);
        }
    }
}
