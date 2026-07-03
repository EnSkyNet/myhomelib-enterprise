package com.myhomelibcorp.ui.model.navigation;

/**
 * Запечатаний інтерфейс для всіх вузлів дерева навігації.
 */
public sealed interface LibraryNode
        permits CollectionNode, AuthorNode, SeriesNode, GenreNode, BookNode, GroupNode {
}