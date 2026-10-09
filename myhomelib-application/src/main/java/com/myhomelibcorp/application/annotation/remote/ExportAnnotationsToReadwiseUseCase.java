package com.myhomelibcorp.application.annotation.remote;

import com.myhomelibcorp.application.annotation.export.AnnotationExportPage;
import com.myhomelibcorp.application.annotation.export.AnnotationExportRow;
import com.myhomelibcorp.application.annotation.export.AnnotationExportSelection;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.annotation.AnnotationExportQueryPort;
import com.myhomelibcorp.application.port.out.annotation.AnnotationRemoteExportPort;
import com.myhomelibcorp.application.port.out.annotation.AnnotationRemoteExportStatePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ExportAnnotationsToReadwiseUseCase {
    static final int PAGE_SIZE = 250;
    static final int BATCH_SIZE = 100;
    private static final String REMOTE = "readwise";

    private final AnnotationExportQueryPort query;
    private final AnnotationRemoteExportPort remote;
    private final AnnotationRemoteExportStatePort state;
    private final LibraryOperationCoordinator operations;

    public ExportAnnotationsToReadwiseUseCase(AnnotationExportQueryPort query,
                                               AnnotationRemoteExportPort remote,
                                               AnnotationRemoteExportStatePort state) {
        this(query, remote, state, null);
    }

    @Autowired
    public ExportAnnotationsToReadwiseUseCase(AnnotationExportQueryPort query,
                                               AnnotationRemoteExportPort remote,
                                               AnnotationRemoteExportStatePort state,
                                               LibraryOperationCoordinator operations) {
        this.query = Objects.requireNonNull(query);
        this.remote = Objects.requireNonNull(remote);
        this.state = Objects.requireNonNull(state);
        this.operations = operations;
    }

    public ReadwiseExportResult export(AnnotationExportSelection selection) {
        if (operations == null) return exportLocked(selection);
        var lease = operations.acquireDetachedShared(LibraryOperationType.EXPORT);
        try {
            ReadwiseExportResult result = exportLocked(selection);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException failure) {
            lease.markFailed(failure);
            throw failure;
        } finally { lease.close(); }
    }

    public void testConnection() { remote.testConnection(); }

    private ReadwiseExportResult exportLocked(AnnotationExportSelection selection) {
        AnnotationExportSelection effective = selection == null ? AnnotationExportSelection.all() : selection;
        long scanned = 0, exported = 0, unchanged = 0;
        int batches = 0, offset = 0;
        List<RemoteAnnotationItem> pending = new ArrayList<>(BATCH_SIZE);
        while (true) {
            AnnotationExportPage page = query.query(effective, offset, PAGE_SIZE);
            for (AnnotationExportRow row : page.items()) {
                scanned++;
                String fingerprint = fingerprint(row);
                if (state.exportedFingerprint(REMOTE, row.id()).filter(fingerprint::equals).isPresent()) {
                    unchanged++;
                    continue;
                }
                pending.add(toRemote(row, fingerprint));
                if (pending.size() == BATCH_SIZE) {
                    exported += flush(pending); batches++; pending.clear();
                }
            }
            if (!page.hasNext()) break;
            offset += page.limit();
        }
        if (!pending.isEmpty()) { exported += flush(pending); batches++; }
        return new ReadwiseExportResult(scanned, exported, unchanged, batches);
    }

    private long flush(List<RemoteAnnotationItem> batch) {
        List<RemoteAnnotationItem> immutable = List.copyOf(batch);
        var result = remote.exportBatch(immutable);
        Map<String, String> accepted = new LinkedHashMap<>();
        for (RemoteAnnotationItem item : immutable) {
            if (result.acceptedAnnotationIds().contains(item.annotationId())) {
                accepted.put(item.annotationId(), item.fingerprint());
            }
        }
        state.markExported(REMOTE, accepted);
        return accepted.size();
    }

    static RemoteAnnotationItem toRemote(AnnotationExportRow row, String fingerprint) {
        return new RemoteAnnotationItem(row.id(), row.bookId(), row.bookTitle(), row.authors(),
                row.quote(), row.note(), row.tags(), row.position(), row.createdAt(), fingerprint);
    }

    static String fingerprint(AnnotationExportRow row) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = String.join("\u001f", row.id(), row.bookId(), row.updatedAt().toString(),
                    row.quote(), row.note(), String.join("\u001e", row.tags()), Double.toString(row.position()));
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
