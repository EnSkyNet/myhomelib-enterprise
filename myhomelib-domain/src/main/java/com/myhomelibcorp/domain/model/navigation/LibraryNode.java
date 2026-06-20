package com.myhomelibcorp.domain.model.navigation;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.series.Series;

/**
 * Запечатаний інтерфейс для всіх вузлів дерева навігації.
 */
public sealed interface LibraryNode
        permits CollectionNode, AuthorNode, SeriesNode, GenreNode, BookNode, GroupNode {
}