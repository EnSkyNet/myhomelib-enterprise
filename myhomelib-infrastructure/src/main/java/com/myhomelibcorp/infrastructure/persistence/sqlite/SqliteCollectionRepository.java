package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.EncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteCollectionRepository implements CollectionRepository {

    @Qualifier("metadataJdbcTemplate")
    private final JdbcTemplate metadataJdbcTemplate;

    private final RowMapper<Collection> collectionRowMapper = (rs, rowNum) -> {
        String id = rs.getString("id");
        String name = rs.getString("name");
        String rootFolder = rs.getString("root_folder");
        String dbFile = rs.getString("db_file");
        int type = rs.getInt("type");
        String user = rs.getString("user");
        String password = rs.getString("password");
        String url = rs.getString("url");
        String notes = rs.getString("notes");
        return new Collection(
                id,
                name,
                rootFolder != null ? java.nio.file.Paths.get(rootFolder) : null,
                dbFile,
                type,
                user,
                password,
                url,
                notes
        );
    };

    @Override
    public List<Collection> findAll() {
        String sql = "SELECT * FROM collections ORDER BY name";
        try {
            List<Collection> collections = metadataJdbcTemplate.query(sql, collectionRowMapper);
            log.info("Завантажено {} колекцій з мета-БД", collections.size());
            for (Collection c : collections) {
                log.info("  - Колекція: id={}, name={}, dbFile={}", c.getId(), c.getName(), c.getDbFile());
            }
            return collections;
        } catch (Exception e) {
            log.error("Помилка завантаження колекцій з мета-БД", e);
            return List.of();
        }
    }

    @Override
    public Optional<Collection> findById(String id) {
        String sql = "SELECT * FROM collections WHERE id = ?";
        try {
            Collection collection = metadataJdbcTemplate.queryForObject(sql, collectionRowMapper, id);
            return Optional.of(collection);
        } catch (Exception e) {
            log.warn("Колекцію з ID {} не знайдено", id);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Collection> findByName(String name) {
        String sql = "SELECT * FROM collections WHERE name = ?";
        try {
            Collection collection = metadataJdbcTemplate.queryForObject(sql, collectionRowMapper, name);
            return Optional.of(collection);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public Collection save(Collection collection) {
        log.info("Збереження колекції: name={}, id={}", collection.getName(), collection.getId());

        String password = collection.getPassword();
        if (password != null && !password.isEmpty() && !EncryptionUtil.isEncrypted(password)) {
            password = EncryptionUtil.encrypt(password);
        }

        if (collection.getId() == null) {
            // Нова колекція - генеруємо ID
            String id = UUID.randomUUID().toString();
            String sql = """
                INSERT INTO collections (id, name, root_folder, db_file, type, user, password, url, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            int updated = metadataJdbcTemplate.update(sql,
                    id,
                    collection.getName(),
                    collection.getRootFolder() != null ? collection.getRootFolder().toString() : null,
                    collection.getDbFile(),
                    collection.getType(),
                    collection.getUser(),
                    password,
                    collection.getUrl(),
                    collection.getNotes()
            );

            log.info("INSERT колекції: rowsUpdated={}, id={}, name={}", updated, id, collection.getName());

            if (updated > 0) {
                Optional<Collection> saved = findById(id);
                if (saved.isPresent()) {
                    log.info("✅ Колекцію успішно збережено: {}", saved.get().getName());
                    return saved.get();
                }
            }
            throw new RuntimeException("Не вдалося створити колекцію: " + collection.getName());
        } else {
            // Перевіряємо, чи існує колекція з таким ID
            boolean exists = false;
            try {
                Integer count = metadataJdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM collections WHERE id = ?", Integer.class, collection.getId());
                exists = count != null && count > 0;
            } catch (Exception e) {
                exists = false;
            }

            if (!exists) {
                // ID передано, але запису немає - виконуємо INSERT
                String sql = """
                    INSERT INTO collections (id, name, root_folder, db_file, type, user, password, url, notes)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;

                int updated = metadataJdbcTemplate.update(sql,
                        collection.getId(),
                        collection.getName(),
                        collection.getRootFolder() != null ? collection.getRootFolder().toString() : null,
                        collection.getDbFile(),
                        collection.getType(),
                        collection.getUser(),
                        password,
                        collection.getUrl(),
                        collection.getNotes()
                );

                log.info("INSERT (з існуючим ID) колекції: rowsUpdated={}, id={}", updated, collection.getId());

                if (updated > 0) {
                    Optional<Collection> saved = findById(collection.getId());
                    if (saved.isPresent()) {
                        return saved.get();
                    }
                }
                throw new RuntimeException("Не вдалося створити колекцію: " + collection.getName());
            } else {
                // Оновлення існуючої колекції
                String sql = """
                    UPDATE collections SET
                        name = ?, root_folder = ?, db_file = ?, type = ?,
                        user = ?, password = ?, url = ?, notes = ?
                    WHERE id = ?
                    """;
                int updated = metadataJdbcTemplate.update(sql,
                        collection.getName(),
                        collection.getRootFolder() != null ? collection.getRootFolder().toString() : null,
                        collection.getDbFile(),
                        collection.getType(),
                        collection.getUser(),
                        password,
                        collection.getUrl(),
                        collection.getNotes(),
                        collection.getId()
                );

                log.info("UPDATE колекції: rowsUpdated={}, id={}, name={}", updated, collection.getId(), collection.getName());
                return collection;
            }
        }
    }

    @Override
    public void deleteById(String id) {
        int deleted = metadataJdbcTemplate.update("DELETE FROM collections WHERE id = ?", id);
        log.info("Колекцію з ID {} видалено, rowsDeleted={}", id, deleted);
    }

    @Override
    public void addBookToCollection(String collectionId, String bookId) {
        metadataJdbcTemplate.update(
                "INSERT OR IGNORE INTO collection_books (collection_id, book_id) VALUES (?, ?)",
                collectionId, bookId
        );
        log.debug("Книгу {} додано до колекції {}", bookId, collectionId);
    }

    @Override
    public void removeBookFromCollection(String collectionId, String bookId) {
        metadataJdbcTemplate.update(
                "DELETE FROM collection_books WHERE collection_id = ? AND book_id = ?",
                collectionId, bookId
        );
        log.debug("Книгу {} видалено з колекції {}", bookId, collectionId);
    }

    @Override
    public List<String> findBookIdsByCollection(String collectionId) {
        return metadataJdbcTemplate.query(
                "SELECT book_id FROM collection_books WHERE collection_id = ?",
                (rs, rowNum) -> rs.getString("book_id"),
                collectionId
        );
    }

    @Override
    public boolean isBookInCollection(String collectionId, String bookId) {
        Integer count = metadataJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM collection_books WHERE collection_id = ? AND book_id = ?",
                Integer.class,
                collectionId, bookId
        );
        return count != null && count > 0;
    }
}