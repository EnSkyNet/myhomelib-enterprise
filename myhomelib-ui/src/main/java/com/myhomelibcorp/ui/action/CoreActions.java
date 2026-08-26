package com.myhomelibcorp.ui.action;

import java.util.List;

/** Stage 14 command catalogue: command IDs and defaults are no longer hidden in FXML/controllers. */
public final class CoreActions {
    private CoreActions() { }

    public static final ActionDefinition NAV_BACK = new ActionDefinition("navigation.back", "Назад", "Alt+Left", true);
    public static final ActionDefinition NAV_FORWARD = new ActionDefinition("navigation.forward", "Вперед", "Alt+Right", true);
    public static final ActionDefinition HELP_CONTEXT = new ActionDefinition("help.context", "Довідка (F1)", "F1", true);
    public static final ActionDefinition SEARCH_FOCUS = new ActionDefinition("search.focus", "Фокус пошуку", "Ctrl+F", true);
    public static final ActionDefinition VIEW_REFRESH = new ActionDefinition("view.refresh", "Оновити", "F5", true);
    public static final ActionDefinition BOOK_OPEN_INTERNAL = new ActionDefinition("book.open.internal", "Відкрити у вбудованому Reader", "Ctrl+Enter", true);
    public static final ActionDefinition BOOK_OPEN_EXTERNAL = new ActionDefinition("book.open.external", "Відкрити у зовнішній читалці", "Ctrl+Shift+Enter", true);
    public static final ActionDefinition COLLECTION_MANAGE = new ActionDefinition("collection.manage", "Керування колекціями...", "Ctrl+Shift+L", true);
    public static final ActionDefinition IMPORT_INPX = new ActionDefinition("import.inpx", "Імпорт INPX...", "Ctrl+I", true);
    public static final ActionDefinition EXPORT_BOOKS = new ActionDefinition("export.books", "Експорт книг / на пристрій...", "Ctrl+E", true);
    public static final ActionDefinition SETTINGS = new ActionDefinition("settings.open", "Налаштування...", "Ctrl+P", true);
    public static final ActionDefinition BOOK_ACTIONS = new ActionDefinition("bookActions.manage", "Дії з книгою / скрипти...", "", true);
    public static final ActionDefinition ACTIONS_CUSTOMIZE = new ActionDefinition("actions.customize", "Команди та гарячі клавіші...", "", true);
    public static final ActionDefinition OPDS_MANAGE = new ActionDefinition("opds.manage", "OPDS сервер...", "", true);

    public static List<ActionDefinition> all() {
        return List.of(NAV_BACK, NAV_FORWARD, HELP_CONTEXT, SEARCH_FOCUS, VIEW_REFRESH,
                BOOK_OPEN_INTERNAL, BOOK_OPEN_EXTERNAL, COLLECTION_MANAGE, IMPORT_INPX,
                EXPORT_BOOKS, SETTINGS, BOOK_ACTIONS, ACTIONS_CUSTOMIZE, OPDS_MANAGE);
    }
}
