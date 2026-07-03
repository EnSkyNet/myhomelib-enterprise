package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.GroupRepository;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import com.myhomelibcorp.infrastructure.persistence.mapper.GroupRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "app.database.type", havingValue = "sqlite", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SqliteGroupRepository implements GroupRepository {

    private final JdbcTemplate jdbcTemplate;
    private final GroupRowMapper groupRowMapper;

    @Override
    public List<Group> findAll() {
        String sql = "SELECT * FROM groups ORDER BY name";
        return jdbcTemplate.query(sql, groupRowMapper);
    }

    @Override
    public Optional<Group> findById(Long id) {
        String sql = "SELECT * FROM groups WHERE id = ?";
        try {
            Group group = jdbcTemplate.queryForObject(sql, groupRowMapper, id);
            return Optional.of(group);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Group> findByName(String name) {
        String sql = "SELECT * FROM groups WHERE name = ?";
        try {
            Group group = jdbcTemplate.queryForObject(sql, groupRowMapper, name);
            return Optional.of(group);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Group save(Group group) {
        if (group.getId() == null || group.getId().asLong() == null) {
            String sql = "INSERT INTO groups (name, allow_delete) VALUES (?, ?)";
            jdbcTemplate.update(sql, group.getName(), group.isAllowDelete() ? 1 : 0);
            Long id = jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
            return new Group(GroupId.fromLong(id), group.getName(), group.isAllowDelete());
        } else {
            String sql = "UPDATE groups SET name = ?, allow_delete = ? WHERE id = ?";
            jdbcTemplate.update(sql, group.getName(), group.isAllowDelete() ? 1 : 0, group.getId().asLong());
            return group;
        }
    }

    @Override
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM book_groups WHERE group_id = ?", id);
        jdbcTemplate.update("DELETE FROM groups WHERE id = ?", id);
        log.info("Групу з ID {} видалено", id);
    }

    @Override
    public void deleteAllBooksFromGroup(Long groupId) {
        jdbcTemplate.update("DELETE FROM book_groups WHERE group_id = ?", groupId);
        log.info("Всі книги видалені з групи {}", groupId);
    }

    @Override
    public void addBookToGroup(Long groupId, String bookId) {
        jdbcTemplate.update(
                "INSERT OR IGNORE INTO book_groups (book_id, group_id) VALUES (?, ?)",
                bookId, groupId
        );
        log.debug("Книгу {} додано до групи {}", bookId, groupId);
    }

    @Override
    public void removeBookFromGroup(Long groupId, String bookId) {
        jdbcTemplate.update(
                "DELETE FROM book_groups WHERE book_id = ? AND group_id = ?",
                bookId, groupId
        );
        log.debug("Книгу {} видалено з групи {}", bookId, groupId);
    }

    @Override
    public List<String> findBookIdsByGroup(Long groupId) {
        String sql = "SELECT book_id FROM book_groups WHERE group_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("book_id"), groupId);
    }
}