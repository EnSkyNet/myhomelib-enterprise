package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.text.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Lightweight built-in Ukrainian/English UI localization, matching classic MyHomeLib restart semantics. */
@Component
@RequiredArgsConstructor
public class LocalizationService {
    private final ApplicationSettingsPort settings;
    private final SignedLanguageCatalogService externalCatalogs;

    private static final Map<String,String> EN = Map.ofEntries(
        Map.entry("Колекція","Collection"), Map.entry("Створити колекцію...","Create collection..."), Map.entry("Перейменувати колекцію...","Rename collection..."),
        Map.entry("Видалити колекцію","Delete collection"), Map.entry("Вибрати колекцію","Select collection"), Map.entry("Майстер створення колекції...","Collection wizard..."),
        Map.entry("Підключити існуючу .hlc2 / SQLite...","Attach existing .hlc2 / SQLite..."), Map.entry("Оновити колекцію з INPX...","Update collection from INPX..."),
        Map.entry("Оновити колекцію з мережі...","Update collection from network..."), Map.entry("Скасувати оновлення колекції","Cancel collection update"),
        Map.entry("Експорт користувацьких даних...","Export user data..."), Map.entry("Імпорт користувацьких даних...","Import user data..."),
        Map.entry("Копіювати вибрані книги в іншу колекцію...","Copy selected books to another collection..."), Map.entry("Вийти","Exit"),
        Map.entry("Група","Group"), Map.entry("Групи (списки книг)","Groups (book lists)"), Map.entry("Додати групу...","Add group..."), Map.entry("Редагувати групу...","Edit group..."),
        Map.entry("Видалити групу","Delete group"), Map.entry("Очистити групу","Clear group"), Map.entry("Редагування","Edit"),
        Map.entry("Редагувати метадані...","Edit metadata..."), Map.entry("Видалити книгу","Delete book"), Map.entry("Відкрити у вбудованому Reader","Open in built-in Reader"),
        Map.entry("Відкрити у зовнішній читалці","Open in external reader"), Map.entry("Завантажити книгу","Download book"), Map.entry("Видалити локальну копію","Remove local copy"), Map.entry("Скасувати завантаження","Cancel download"),
        Map.entry("Закрити Reader","Close Reader"), Map.entry("Вигляд","View"), Map.entry("Оновити","Refresh"), Map.entry("Показати колонки...","Choose columns..."),
        Map.entry("Нові книги","New books"), Map.entry("Історія читання","Reading history"), Map.entry("Інструменти","Tools"),
        Map.entry("Імпорт книги/архіву...","Import book/archive..."), Map.entry("Імпорт INPX...","Import INPX..."), Map.entry("Імпорт каталогу...","Import folder..."),
        Map.entry("Експорт книг / на пристрій...","Export books / send to device..."), Map.entry("Експорт поточного списку","Export current list"),
        Map.entry("Перевірити цілісність...","Check integrity..."), Map.entry("Оптимізувати БД (VACUUM)","Optimize DB (VACUUM)"),
        Map.entry("Перебудувати індекс...","Rebuild index..."), Map.entry("Резервне копіювання...","Backup..."), Map.entry("Відновити з копії...","Restore backup..."),
        Map.entry("Статистика","Statistics"), Map.entry("Синхронізувати папку...","Synchronize folder..."), Map.entry("Налаштування...","Settings..."),
        Map.entry("Допомога","Help"), Map.entry("Довідка (F1)","Help (F1)"), Map.entry("Про програму","About"),
        Map.entry("Пошук...","Search..."), Map.entry("Назва, автор, серія, жанр...","Title, author, series, genre..."), Map.entry("+Імпорт","+Import"), Map.entry("+Книга","+Book"),
        Map.entry("Оцінити","Rate"), Map.entry("Прочитано","Read"), Map.entry("Додати до групи","Add to group"), Map.entry("Зняти вибір","Clear selection"), Map.entry("Експорт","Export"),
        Map.entry("Завантажити","Download"), Map.entry("⌫ Локальна копія","⌫ Local copy"), Map.entry("Книги","Books"), Map.entry("Автори","Authors"), Map.entry("Серії","Series"), Map.entry("Жанри","Genres"),
        Map.entry("Автор","Author"), Map.entry("Назва","Title"), Map.entry("Серія","Series"), Map.entry("Жанр","Genre"), Map.entry("Мова","Language"), Map.entry("Рік","Year"),
        Map.entry("Видавництво","Publisher"), Map.entry("Анотація","Annotation"), Map.entry("Ключові слова","Keywords"), Map.entry("Оцінка","Rating"), Map.entry("Прогрес","Progress"),
        Map.entry("Файл","File"), Map.entry("Розмір","Size"), Map.entry("Формат","Format"), Map.entry("Відкрити","Open"), Map.entry("Читати","Read"),
        Map.entry("Видалити","Delete"), Map.entry("Редагувати","Edit"), Map.entry("Закрити","Close"), Map.entry("Скасувати","Cancel"), Map.entry("Зберегти","Save"), Map.entry("Шукати","Search"),
        Map.entry("Розширений пошук (як у класичному MyHomeLib)","Advanced search (classic MyHomeLib compatible)"),
        Map.entry("Введіть текст для пошуку","Enter search text"), Map.entry("Введіть запит для пошуку","Enter search query"), Map.entry("Оцінка бібліотеки від/до","Library rating from/to"),
        Map.entry("Рік від/до","Year from/to"), Map.entry("Додано від/до","Added from/to"), Map.entry("Тільки локальні","Local only"), Map.entry("Збережені пошуки","Saved searches"),
        Map.entry("Зберегти поточний пошук:","Save current search:"), Map.entry("Список збережених пошуків:","Saved searches:"),
        Map.entry("Налаштування","Settings"), Map.entry("Коренева папка:","Root folder:"), Map.entry("Шлях до БД:","Database path:"), Map.entry("Користувач:","User:"), Map.entry("Пароль:","Password:"),
        Map.entry("Назва колекції:*","Collection name:*"), Map.entry("Тип:","Type:"), Map.entry("Джерело:","Source:"), Map.entry("Файл джерела:","Source file:"),
        Map.entry("Імпортувати при створенні","Import on creation"), Map.entry("Створити пошуковий індекс","Create search index"), Map.entry("Далі →","Next →"), Map.entry("← Назад","← Back"),
        Map.entry("✅ Створити","✅ Create"), Map.entry("📚 Майстер створення колекції","📚 Collection Wizard"), Map.entry("📚 Ласкаво просимо","📚 Welcome"),
        Map.entry("📊 Статистика колекції","📊 Collection statistics"), Map.entry("📊 Статистика імпорту","📊 Import statistics"), Map.entry("📂 Імпорт бібліотеки","📂 Library import"),
        Map.entry("🔎 Пошук у бібліотеці","🔎 Library search"), Map.entry("📑 Зміст","📑 Contents"), Map.entry("🔍 Пошук в книзі","🔍 Search in book"),
        Map.entry("Імпортувати файл","Import file"), Map.entry("Імпортувати папку","Import folder"), Map.entry("Виберіть файл (FB2, INPX...)","Choose a file (FB2, INPX...)"),
        Map.entry("Виберіть папку з книгами","Choose a folder with books"), Map.entry("Імпортовано книг:","Books imported:"), Map.entry("Помилок:","Errors:"),
        Map.entry("Готово до роботи","Ready"), Map.entry("Готово до імпорту","Ready to import"), Map.entry("Немає книг","No books"), Map.entry("0 книг","0 books"), Map.entry("0 книг вибрано","0 books selected"),
        Map.entry("Ім'я файлу","File name"), Map.entry("Назва або серія...","Title or series..."), Map.entry("Фільтр:","Filter:"), Map.entry("За назвою","By title"), Map.entry("За рейтингом","By rating"), Map.entry("За роком","By year")
    );


    private static final Map<String,String> BG = Map.ofEntries(
        Map.entry("Колекція","Колекция"), Map.entry("Створити колекцію...","Създаване на колекция..."), Map.entry("Перейменувати колекцію...","Преименуване на колекция..."),
        Map.entry("Видалити колекцію","Изтриване на колекция"), Map.entry("Вибрати колекцію","Избор на колекция"), Map.entry("Майстер створення колекції...","Помощник за колекция..."),
        Map.entry("Підключити існуючу .hlc2 / SQLite...","Свързване на съществуваща .hlc2 / SQLite..."), Map.entry("Властивості колекції...","Свойства на колекцията..."),
        Map.entry("Оновити колекцію з INPX...","Обновяване от INPX..."), Map.entry("Оновити колекцію з мережі...","Обновяване от мрежата..."), Map.entry("Скасувати оновлення колекції","Отказ на обновяването"),
        Map.entry("Експорт користувацьких даних...","Експорт на потребителски данни..."), Map.entry("Імпорт користувацьких даних...","Импорт на потребителски данни..."),
        Map.entry("Копіювати вибрані книги в іншу колекцію...","Копиране на избраните книги в друга колекция..."), Map.entry("Вийти","Изход"),
        Map.entry("Група","Група"), Map.entry("Додати групу...","Добавяне на група..."), Map.entry("Редагувати групу...","Редактиране на група..."), Map.entry("Видалити групу","Изтриване на група"), Map.entry("Очистити групу","Изчистване на група"),
        Map.entry("Редагування","Редактиране"), Map.entry("Редагувати метадані...","Редактиране на метаданни..."), Map.entry("Видалити книгу","Изтриване на книга"),
        Map.entry("Reader","Четец"), Map.entry("Відкрити у вбудованому Reader","Отваряне във вградения четец"), Map.entry("Відкрити у зовнішній читалці","Отваряне във външен четец"),
        Map.entry("Завантажити книгу","Изтегляне на книга"), Map.entry("Видалити локальну копію","Изтриване на локалното копие"), Map.entry("Скасувати завантаження","Отказ на изтеглянето"), Map.entry("Закрити Reader","Затваряне на четеца"),
        Map.entry("Вигляд","Изглед"), Map.entry("Оновити","Обновяване"), Map.entry("Показати колонки...","Показване на колони..."), Map.entry("Нові книги","Нови книги"), Map.entry("Історія читання","История на четенето"),
        Map.entry("Інструменти","Инструменти"), Map.entry("Імпорт книги/архіву...","Импорт на книга/архив..."), Map.entry("Імпорт INPX...","Импорт на INPX..."), Map.entry("Імпорт каталогу...","Импорт на папка..."),
        Map.entry("Експорт книг / на пристрій...","Експорт на книги / към устройство..."), Map.entry("Експорт поточного списку","Експорт на текущия списък"),
        Map.entry("Перевірити цілісність...","Проверка на целостта..."), Map.entry("Оптимізувати БД (VACUUM)","Оптимизиране на БД (VACUUM)"), Map.entry("Перебудувати індекс...","Преизграждане на индекса..."),
        Map.entry("Резервне копіювання...","Резервно копие..."), Map.entry("Відновити з копії...","Възстановяване от копие..."), Map.entry("Статистика","Статистика"), Map.entry("Синхронізувати папку...","Синхронизиране на папка..."), Map.entry("Налаштування...","Настройки..."),
        Map.entry("Допомога","Помощ"), Map.entry("Довідка (F1)","Помощ (F1)"), Map.entry("Про програму","За програмата"),
        Map.entry("Пошук...","Търсене..."), Map.entry("Назва, автор, серія, жанр...","Заглавие, автор, серия, жанр..."), Map.entry("Книги","Книги"), Map.entry("Автори","Автори"), Map.entry("Серії","Серии"), Map.entry("Жанри","Жанрове"),
        Map.entry("Автор","Автор"), Map.entry("Назва","Заглавие"), Map.entry("Серія","Серия"), Map.entry("Жанр","Жанр"), Map.entry("Мова","Език"), Map.entry("Рік","Година"), Map.entry("Видавництво","Издателство"), Map.entry("Анотація","Анотация"), Map.entry("Ключові слова","Ключови думи"),
        Map.entry("Оцінка","Оценка"), Map.entry("Прогрес","Напредък"), Map.entry("Файл","Файл"), Map.entry("Розмір","Размер"), Map.entry("Формат","Формат"), Map.entry("Відкрити","Отваряне"), Map.entry("Читати","Четене"),
        Map.entry("Видалити","Изтриване"), Map.entry("Редагувати","Редактиране"), Map.entry("Закрити","Затваряне"), Map.entry("Скасувати","Отказ"), Map.entry("Зберегти","Запазване"), Map.entry("Шукати","Търсене"), Map.entry("Очистити","Изчистване"),
        Map.entry("Розширений пошук (як у класичному MyHomeLib)","Разширено търсене (като в класическия MyHomeLib)"), Map.entry("Тільки локальні","Само локални"), Map.entry("Збережені пошуки","Запазени търсения"),
        Map.entry("Налаштування","Настройки"), Map.entry("Коренева папка:","Основна папка:"), Map.entry("Шлях до БД:","Път до БД:"), Map.entry("Користувач:","Потребител:"), Map.entry("Пароль:","Парола:"), Map.entry("Тип:","Тип:"), Map.entry("Джерело:","Източник:"), Map.entry("Файл джерела:","Файл източник:"),
        Map.entry("Імпортувати при створенні","Импортиране при създаване"), Map.entry("Створити пошуковий індекс","Създаване на индекс за търсене"), Map.entry("Далі →","Напред →"), Map.entry("← Назад","← Назад"), Map.entry("✅ Створити","✅ Създаване"),
        Map.entry("📚 Майстер створення колекції","📚 Помощник за създаване на колекция"), Map.entry("📂 Імпорт бібліотеки","📂 Импорт на библиотека"), Map.entry("🔎 Пошук у бібліотеці","🔎 Търсене в библиотеката"), Map.entry("📑 Зміст","📑 Съдържание"), Map.entry("🔍 Пошук в книзі","🔍 Търсене в книгата"),
        Map.entry("Імпортувати файл","Импорт на файл"), Map.entry("Імпортувати папку","Импорт на папка"), Map.entry("Готово до роботи","Готово"), Map.entry("Немає книг","Няма книги"), Map.entry("Ім'я файлу","Име на файл"), Map.entry("Фільтр:","Филтър:")
    );

    public String language() {
        String value = normalizeLanguage(settings.get("ui.language", "uk"));
        if ("uk".equals(value) || "en".equals(value) || "bg".equals(value) || externalCatalogs.hasLanguage(value)) return value;
        return "uk";
    }
    public void setLanguage(String language) {
        String value = normalizeLanguage(language);
        if (!("uk".equals(value) || "en".equals(value) || "bg".equals(value) || externalCatalogs.hasLanguage(value))) value = "uk";
        settings.put("ui.language", value);
    }
    public Map<String, String> availableLanguages() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        result.put("uk", "Українська");
        result.put("en", "English");
        // Bulgarian remains bundled as an additional translation; external signed catalogues are appended below.
        result.put("bg", "Български");
        externalCatalogs.availableLanguages().forEach(result::putIfAbsent);
        return java.util.Collections.unmodifiableMap(result);
    }
    public boolean isEnglish() { return "en".equals(language()); }
    public boolean isBulgarian() { return "bg".equals(language()); }
    public String tr(String text) {
        if (text == null) return null;
        String lang = language();
        if ("en".equals(lang)) return EN.getOrDefault(text, text);
        if ("bg".equals(lang)) return BG.getOrDefault(text, text);
        if (!"uk".equals(lang)) return externalCatalogs.translations(lang).map(m -> m.getOrDefault(text, text)).orElse(text);
        return text;
    }
    private static String normalizeLanguage(String language) {
        if (language == null) return "uk";
        String value = language.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        return value.matches("[a-z]{2,3}(-[a-z0-9]{2,8})?") ? value : "uk";
    }


    public void apply(Parent root) {
        if ("uk".equals(language()) || root == null) return;
        translateNode(root);
    }

    private void translateNode(Node node) {
        if (node instanceof Labeled l) l.setText(tr(l.getText()));
        if (node instanceof TextInputControl t) t.setPromptText(tr(t.getPromptText()));
        if (node instanceof Text t) t.setText(tr(t.getText()));
        if (node instanceof MenuBar mb) for (Menu menu : mb.getMenus()) translateMenu(menu);
        if (node instanceof TableView<?> tv) for (TableColumn<?,?> c : tv.getColumns()) translateColumn(c);
        if (node instanceof TreeTableView<?> tv) for (TreeTableColumn<?,?> c : tv.getColumns()) translateTreeColumn(c);
        if (node instanceof TabPane tp) for (Tab tab : tp.getTabs()) { tab.setText(tr(tab.getText())); if (tab.getContent()!=null) translateNode(tab.getContent()); }
        if (node instanceof Parent p) for (Node child : p.getChildrenUnmodifiable()) translateNode(child);
    }
    private void translateMenu(MenuItem item) { item.setText(tr(item.getText())); if (item instanceof Menu m) for (MenuItem child : m.getItems()) translateMenu(child); }
    private void translateColumn(TableColumn<?,?> c) { c.setText(tr(c.getText())); for (TableColumn<?,?> x : c.getColumns()) translateColumn(x); }
    private void translateTreeColumn(TreeTableColumn<?,?> c) { c.setText(tr(c.getText())); for (TreeTableColumn<?,?> x : c.getColumns()) translateTreeColumn(x); }
}
