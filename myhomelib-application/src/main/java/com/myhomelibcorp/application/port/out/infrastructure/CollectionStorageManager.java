package com.myhomelibcorp.application.port.out.infrastructure;

import com.myhomelibcorp.domain.model.collection.Collection;

public interface CollectionStorageManager {

    /**
     * Закриває всі ресурси, пов'язані з колекцією (DataSource, Lucene тощо).
     */
    void closeCollection(Collection collection);

    /**
     * Видаляє всі фізичні файли колекції (БД, індекси, обкладинки, temp).
     * Використовується для rollback новоствореної/невдалої колекції, де registry commit
     * не є окремою crash-recoverable транзакцією. Для звичайного Delete Collection
     * слід використовувати prepare/commit/rollback протокол нижче.
     */
    void deletePhysicalFiles(Collection collection);

    /**
     * Видаляє лише похідні/відновлювані дані колекції (Lucene, content index, temp),
     * не торкаючись SQLite БД. Використовується для attached/UNKNOWN database ownership.
     */
    void deleteDerivedFiles(Collection collection);

    /**
     * Crash-recoverable фаза 1 видалення: durable marker створюється до того, як основна
     * SQLite БД від'єднується у tombstone. Registry на цьому етапі ще не змінюється.
     */
    void preparePhysicalDeletion(Collection collection);

    /**
     * Фаза 2 після успішного видалення registry-запису. Видаляє tombstone та derived data.
     * Якщо cleanup перерветься, startup recovery має завершити його за durable marker.
     */
    void commitPreparedPhysicalDeletion(Collection collection);

    /**
     * Відкат фази 1, якщо registry-запис не вдалося видалити. Повертає SQLite БД на
     * початковий шлях лише якщо tombstone все ще належить цій операції.
     */
    void rollbackPreparedPhysicalDeletion(Collection collection);

    /**
     * Виконує VACUUM для активної колекції. Storage manager працює з current DataSource,
     * тому API навмисно не приймає довільну Collection.
     */
    void vacuumCurrent();
}