package com.myhomelibcorp.application.port.out.infrastructure;

import com.myhomelibcorp.domain.model.collection.Collection;

public interface CollectionStorageManager {

    /**
     * Закриває всі ресурси, пов'язані з колекцією (DataSource, Lucene тощо).
     */
    void closeCollection(Collection collection);

    /**
     * Видаляє всі фізичні файли колекції (БД, індекси, обкладинки, temp).
     */
    void deletePhysicalFiles(Collection collection);

    /**
     * Виконує VACUUM для активної колекції. Storage manager працює з current DataSource,
     * тому API навмисно не приймає довільну Collection.
     */
    void vacuumCurrent();
}