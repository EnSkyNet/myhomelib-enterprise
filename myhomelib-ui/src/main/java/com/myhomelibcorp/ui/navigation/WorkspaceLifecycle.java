package com.myhomelibcorp.ui.navigation;

/**
 * Інтерфейс для workspace, які потребують очищення ресурсів при закритті.
 */
public interface WorkspaceLifecycle {

    /**
     * Вивільняє ресурси workspace перед видаленням.
     * Викликається WorkspaceManager при переході на інший workspace.
     */
    void dispose();
}