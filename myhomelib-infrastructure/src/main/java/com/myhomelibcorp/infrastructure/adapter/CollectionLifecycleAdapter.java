package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionLifecycleAdapter implements CollectionLifecyclePort {

    private final CollectionManager collectionManager;

    @Override
    public void switchToCollection(Collection collection) {
        collectionManager.switchToCollection(collection);
    }

    @Override
    public void closeCurrentCollection() {
        collectionManager.closeCurrentCollection();
    }

    @Override
    public Collection getCurrentCollection() {
        return collectionManager.getCurrentCollection();
    }

    @Override
    public void updateCurrentCollection(Collection collection) {
        collectionManager.updateCurrentCollection(collection);
    }

    @Override
    public boolean hasActiveCollection() {
        return collectionManager.hasActiveCollection();
    }

    @Override
    public boolean isCollectionReady() {
        if (!collectionManager.hasActiveCollection()) {
            return false;
        }
        try {
            var jdbc = collectionManager.getCurrentJdbcTemplate();
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (DataAccessException e) {
            log.debug("Активна колекція ще не готова до SQL-запитів: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public long getDatabaseSize() {
        Collection collection = collectionManager.getCurrentCollection();
        if (collection == null) {
            return 0;
        }
        String dbPath = collection.getDbFile();
        if (dbPath == null || dbPath.isBlank()) {
            return 0;
        }
        try {
            Path safe = SafeFilePublication.requireSafeRegularFile(Path.of(dbPath), "Active collection database");
            BasicFileAttributes attrs = Files.readAttributes(safe, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attrs.isRegularFile() ? attrs.size() : 0L;
        } catch (IOException e) {
            log.warn("Не вдалося визначити розмір БД {}: {}", dbPath, e.getMessage());
            return 0;
        }
    }
}