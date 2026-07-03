package com.myhomelibcorp.infrastructure.persistence.sqlite.query;

public final class GroupQueries {

    private GroupQueries() {}

    public static final String FIND_ALL = "SELECT * FROM groups ORDER BY name";
    public static final String FIND_BY_ID = "SELECT * FROM groups WHERE id = ?";
    public static final String FIND_BY_NAME = "SELECT * FROM groups WHERE name = ?";
    public static final String FIND_BOOK_IDS_BY_GROUP = "SELECT book_id FROM book_groups WHERE group_id = ?";

    public static final String INSERT_GROUP = "INSERT INTO groups (name, allow_delete) VALUES (?, ?)";
    public static final String UPDATE_GROUP = "UPDATE groups SET name = ?, allow_delete = ? WHERE id = ?";
    public static final String DELETE_GROUP = "DELETE FROM groups WHERE id = ?";
    public static final String DELETE_BOOK_GROUPS_BY_GROUP = "DELETE FROM book_groups WHERE group_id = ?";

    public static final String ADD_BOOK_TO_GROUP = "INSERT OR IGNORE INTO book_groups (book_id, group_id) VALUES (?, ?)";
    public static final String REMOVE_BOOK_FROM_GROUP = "DELETE FROM book_groups WHERE book_id = ? AND group_id = ?";
    public static final String DELETE_ALL_BOOKS_FROM_GROUP = "DELETE FROM book_groups WHERE group_id = ?";
}