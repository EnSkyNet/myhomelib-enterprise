package com.myhomelibcorp.ui.model;

import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class TreeNode {

    public enum NodeType {
        ROOT,
        AUTHOR,
        SERIES,
        BOOK
    }

    private final NodeType type;
    private final StringProperty name = new SimpleStringProperty();
    private final ObjectProperty<BookViewModel> book = new SimpleObjectProperty<>();
    private final ObservableList<TreeNode> children = FXCollections.observableArrayList();

    // Конструктори
    public TreeNode(NodeType type, String name) {
        this.type = type;
        this.name.set(name);
    }

    public TreeNode(BookViewModel book) {
        this.type = NodeType.BOOK;
        this.book.set(book);
        this.name.set(book.getTitle());
    }

    // Геттери
    public NodeType getType() {
        return type;
    }

    public String getName() {
        return name.get();
    }

    public StringProperty nameProperty() {
        return name;
    }

    public BookViewModel getBook() {
        return book.get();
    }

    public ObjectProperty<BookViewModel> bookProperty() {
        return book;
    }

    public ObservableList<TreeNode> getChildren() {
        return children;
    }

    public boolean isLeaf() {
        return type == NodeType.BOOK;
    }

    /**
     * Перевіряє, чи вибрана книга
     */
    public boolean isSelected() {
        if (type == NodeType.BOOK && book.get() != null) {
            return book.get().isSelected();
        }
        return false;
    }

    /**
     * Встановлює вибір для книги
     */
    public void setSelected(boolean selected) {
        if (type == NodeType.BOOK && book.get() != null) {
            book.get().setSelected(selected);
        }
    }

    public void addChild(TreeNode child) {
        children.add(child);
    }

    public void addChildren(ObservableList<TreeNode> newChildren) {
        children.addAll(newChildren);
    }

    @Override
    public String toString() {
        return name.get();
    }
}