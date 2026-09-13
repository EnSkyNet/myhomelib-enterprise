package com.myhomelibcorp.application.port.out.sync;

import com.myhomelibcorp.domain.model.sync.ChangeSet;

import java.util.List;
import java.util.Map;

/** Portable change-set transport. Implementations must never synchronize a live database file. */
public interface SyncTransportPort {
    void push(ChangeSet changeSet);

    /** Pulls bundles with a sequence greater than the per-device cursor. */
    List<ChangeSet> pull(Map<String, Long> lastSequenceByDevice);
}
