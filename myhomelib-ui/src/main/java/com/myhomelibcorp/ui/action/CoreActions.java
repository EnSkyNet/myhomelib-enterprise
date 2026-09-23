package com.myhomelibcorp.ui.action;


import com.myhomelibcorp.ui.service.UiText;
import java.util.List;

/** Stage 14 command catalogue: command IDs and defaults are no longer hidden in FXML/controllers. */
public final class CoreActions {
    private CoreActions() { }

    public static final ActionDefinition NAV_BACK = new ActionDefinition("navigation.back", UiText.text("ui.annotations.previous"), "Alt+Left", true);
    public static final ActionDefinition NAV_FORWARD = new ActionDefinition("navigation.forward", UiText.text("ui.main.fxml.forward"), "Alt+Right", true);
    public static final ActionDefinition HELP_CONTEXT = new ActionDefinition("help.context", UiText.text("ui.main.fxml.help_f1"), "F1", true);
    public static final ActionDefinition SEARCH_FOCUS = new ActionDefinition("search.focus", UiText.text("ui.java.core_actions.1a251752bf"), "Ctrl+F", true);
    public static final ActionDefinition VIEW_REFRESH = new ActionDefinition("view.refresh", UiText.text("ui.annotations.refresh"), "F5", true);
    public static final ActionDefinition BOOK_OPEN_INTERNAL = new ActionDefinition("book.open.internal", UiText.text("ui.java.core_actions.c453dddd63"), "Ctrl+Enter", true);
    public static final ActionDefinition BOOK_OPEN_EXTERNAL = new ActionDefinition("book.open.external", UiText.text("ui.main.fxml.open_in_external_reader"), "Ctrl+Shift+Enter", true);
    public static final ActionDefinition COLLECTION_MANAGE = new ActionDefinition("collection.manage", UiText.text("ui.main.fxml.manage_collections"), "Ctrl+Shift+L", true);
    public static final ActionDefinition IMPORT_INPX = new ActionDefinition("import.inpx", UiText.text("ui.main.fxml.import_inpx"), "Ctrl+I", true);
    public static final ActionDefinition EXPORT_BOOKS = new ActionDefinition("export.books", UiText.text("ui.java.core_actions.5f5ba58f1d"), "Ctrl+E", true);
    public static final ActionDefinition SETTINGS = new ActionDefinition("settings.open", UiText.text("ui.main.fxml.settings_2"), "Ctrl+P", true);
    public static final ActionDefinition BOOK_ACTIONS = new ActionDefinition("bookActions.manage", UiText.text("ui.main.fxml.book_actions_scripts"), "", true);
    public static final ActionDefinition ACTIONS_CUSTOMIZE = new ActionDefinition("actions.customize", UiText.text("ui.main.fxml.commands_and_shortcuts"), "", true);
    public static final ActionDefinition OPDS_MANAGE = new ActionDefinition("opds.manage", UiText.text("ui.java.core_actions.f69d7e34fa"), "", true);

    public static List<ActionDefinition> all() {
        return List.of(NAV_BACK, NAV_FORWARD, HELP_CONTEXT, SEARCH_FOCUS, VIEW_REFRESH,
                BOOK_OPEN_INTERNAL, BOOK_OPEN_EXTERNAL, COLLECTION_MANAGE, IMPORT_INPX,
                EXPORT_BOOKS, SETTINGS, BOOK_ACTIONS, ACTIONS_CUSTOMIZE, OPDS_MANAGE);
    }
}
