package com.myhomelibcorp.application.service;

import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.port.out.backup.UserDataTransferPort;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Application orchestration for direct portable user-data import/export. */
@Service
@RequiredArgsConstructor
public class PortableUserDataService {
    private final UserDataTransferPort transfer;
    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final SearchIndexer searchIndexer;

    public UserDataTransferPort.ExportResult exportTo(Path file) throws IOException {
        return transfer.exportTo(file);
    }

    public UserDataTransferPort.ImportResult restoreFrom(Path file) throws IOException {
        UserDataTransferPort.ImportResult result = transfer.restoreFrom(file);
        synchronizeSearch(result.searchChanges());
        return result;
    }

    private void synchronizeSearch(ImportChangeSet changes) throws IOException {
        if (changes == null || (changes.insertedCount() + changes.updatedCount() + changes.deletedCount()) == 0) {
            return;
        }
        if (!changes.complete()) {
            try {
                searchIndexer.rebuildIndex();
            } catch (RuntimeException e) {
                throw new IOException("User data restored, but full search-index rebuild failed", e);
            }
            return;
        }

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.addAll(changes.inserted());
        ids.addAll(changes.updated());
        ids.addAll(changes.deleted());
        List<BookId> bookIds = new ArrayList<>(ids.size());
        for (String id : ids) if (id != null && !id.isBlank()) bookIds.add(BookId.fromString(id));
        if (!searchIndexSynchronizer.synchronizeSafelyNow(bookIds)) {
            throw new IOException("User data restored, but search-index synchronization failed");
        }
    }
}
