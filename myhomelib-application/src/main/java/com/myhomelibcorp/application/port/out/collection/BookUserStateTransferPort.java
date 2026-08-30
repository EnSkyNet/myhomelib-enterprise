package com.myhomelibcorp.application.port.out.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

/**
 * Copies collection-local user state for books that were copied to another collection.
 * The target collection must already be active and the implementation must participate
 * in the caller's target-DB transaction so book rows and user state commit atomically.
 */
public interface BookUserStateTransferPort {
    void transferCopiedBookState(Collection sourceCollection,
                                 Collection targetCollection,
                                 List<BookId> copiedBookIds);
}
