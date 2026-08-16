package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionBackupAdapter implements CollectionBackupPort {

    private final CollectionManager collectionManager;

    @Override
    public Collection getCurrentCollection() {
        return collectionManager.getCurrentCollection();
    }

    @Override
    public String getDatabasePath(Collection collection) {
        String dbPath = collection.getDbFile();
        if (dbPath == null || dbPath.isEmpty()) {
            dbPath = System.getProperty("user.home") + "/.myhomelibcorp/libraries/" + collection.getId() + ".db";
        }
        return dbPath;
    }

    @Override
    public void closeCurrentCollection() {
        collectionManager.closeCurrentCollection();
    }

    @Override
    public boolean hasActiveCollection() {
        return collectionManager.hasActiveCollection();
    }
}