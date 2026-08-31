package com.myhomelibcorp.infrastructure.download.source;

/**
 * Режим завантаження книги з онлайн-джерела.
 */
public enum DownloadMode {
    /**
     * Пряме HTTP завантаження через Collection.url.
     */
    DIRECT_HTTP,

    /**
     * Завантаження через виконуваний ConnectionScript (GET/POST).
     */
    CONNECTION_SCRIPT
}