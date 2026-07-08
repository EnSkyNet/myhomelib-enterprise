package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.domain.model.collection.Collection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
        return metadataJdbcTemplate.query(sql, collectionRowMapper);
    }

    @Override
    public Optional<Collection> findById(String id) {
        String sql = "SELECT * FROM collections WHERE id = ?";
        try {
            Collection collection = metadataJdbcTemplate.queryForObject(sql, collectionRowMapper, id);
            return Optional.of(collection);
        } catch (Exception e) {
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
    public Collection save(Collection collection) {
        if (collection.getId() == null) {
            String id = UUID.randomUUID().toString();
            String sql = """
                INSERT INTO collections (id, name, root_folder, db_file, type, user, password, url, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            metadataJdbcTemplate.update(sql,
                    id,
                    collection.getName(),
                    collection.getRootFolder() != null ? collection.getRootFolder().toString() : null,
                    collection.getDbFile(),
                    collection.getType(),
                    collection.getUser(),
                    collection.getPassword(),
                    collection.getUrl(),
                    collection.getNotes()
            );
            return findById(id).orElseThrow(() -> new RuntimeException("Не вдалося створити колекцію"));
        } else {
            String sql = """
                UPDATE collections SET
                    name = ?, root_folder = ?, db_file = ?, type = ?,
                    user = ?, password = ?, url = ?, notes = ?
                WHERE id = ?
                """;
            metadataJdbcTemplate.update(sql,
                    collection.getName(),
                    collection.getRootFolder() != null ? collection.getRootFolder().toString() : null,
                    collection.getDbFile(),
                    collection.getType(),
                    collection.getUser(),
                    collection.getPassword(),
                    collection.getUrl(),
                    collection.getNotes(),
                    collection.getId()
            );
            return collection;
        }
    }

    @Override
    public void deleteById(String id) {
        metadataJdbcTemplate.update("DELETE FROM collections WHERE id = ?", id);
        log.info("Колекцію з ID {} видалено", id);
    }
}