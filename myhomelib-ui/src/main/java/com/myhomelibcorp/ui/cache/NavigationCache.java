package com.myhomelibcorp.ui.cache;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.ui.model.navigation.AuthorNode;
import com.myhomelibcorp.ui.model.navigation.LibraryNode;
import javafx.scene.control.TreeItem;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кеш для вузлів дерева навігації.
 * Зберігає побудовані TreeItem, щоб не перебудовувати їх при кожному оновленні.
 */
@Component
public class NavigationCache {

    private final Map<String, TreeItem<LibraryNode>> authorCache = new ConcurrentHashMap<>();
    private final Map<String, TreeItem<LibraryNode>> genreCache = new ConcurrentHashMap<>();
    private final Map<String, TreeItem<LibraryNode>> seriesCache = new ConcurrentHashMap<>();

    /**
     * Отримує кешований вузол автора за ID.
     */
    public TreeItem<LibraryNode> getAuthorItem(String authorId) {
        return authorCache.get(authorId);
    }

    /**
     * Зберігає вузол автора в кеш.
     */
    public void putAuthorItem(String authorId, TreeItem<LibraryNode> item) {
        authorCache.put(authorId, item);
    }

    /**
     * Отримує кешований вузол жанру за кодом.
     */
    public TreeItem<LibraryNode> getGenreItem(String genreCode) {
        return genreCache.get(genreCode);
    }

    /**
     * Зберігає вузол жанру в кеш.
     */
    public void putGenreItem(String genreCode, TreeItem<LibraryNode> item) {
        genreCache.put(genreCode, item);
    }

    /**
     * Отримує кешований вузол серії за назвою.
     */
    public TreeItem<LibraryNode> getSeriesItem(String seriesName) {
        return seriesCache.get(seriesName);
    }

    /**
     * Зберігає вузол серії в кеш.
     */
    public void putSeriesItem(String seriesName, TreeItem<LibraryNode> item) {
        seriesCache.put(seriesName, item);
    }

    /**
     * Очищує весь кеш (наприклад, при зміні колекції).
     */
    public void invalidateAll() {
        authorCache.clear();
        genreCache.clear();
        seriesCache.clear();
    }
}