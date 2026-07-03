# ARCHITECTURE.md (оновлено 03.07.2026)

Цей документ описує поточну архітектуру проєкту MyHomeLib Enterprise, фіксує виконані зміни та визначає подальші кроки розвитку.

---

## 1. Бачення проєкту

**MyHomeLib** – це сучасна офлайн-орієнтована система керування домашньою електронною бібліотекою.

Основні принципи:
- **Offline First** – усі функції працюють без Інтернету.
- **Local Database** – єдине джерело істини – локальна БД (SQLite).
- **Domain First** – предметна область є головною.
- **Незалежність від UI** – одна бізнес-логіка для всіх клієнтів (JavaFX, Web, Android).
- **Незалежність від БД** – архітектура дозволяє змінювати SQLite на PostgreSQL без змін у Domain/Application.
- **Незалежність від формату книги** – усі формати (FB2, EPUB, PDF тощо) підтримуються через імпортери.

---

## 2. Поточна архітектура системи (після рефакторингу)

### Загальна схема
JavaFX UI
│
▼
UI Controllers / Presenters
│
▼
Application (Use Cases / Services)
│
▼
Domain Model
│
▼
Infrastructure (SQLite, Lucene, Filesystem, Cover, Importers)

text

### Модулі проєкту

| Модуль | Призначення |
|--------|-------------|
| `myhomelib-domain` | Бізнес-модель (Book, Author, Genre, Value Objects, Events) |
| `myhomelib-application` | Сценарії роботи (Use Cases), порти, DTO |
| `myhomelib-infrastructure` | Реалізація портів (SQLite, Lucene, імпортери, обкладинки) |
| `myhomelib-ui` | JavaFX-інтерфейс (контролери, презентери, сервіси, вьюмоделі, мапери) |
| `myhomelib-bootstrap` | Збірка та запуск застосунку |
| `myhomelib-architecture-tests` | Архітектурні тести (ArchUnit) |
| `myhomelib-e2e-tests` | End-to-end тести |

---

## 3. Структура пакетів (поточна)

### Domain
domain
├── model
│ ├── book (Book, BookSnapshot)
│ ├── author (Author)
│ ├── genre (Genre)
│ ├── group (Group)
│ ├── series (Series)
│ ├── collection (Collection)
│ └── cover (Cover)
├── event (BookDeletedEvent, BookUpdatedEvent)
└── valueobject (BookId, AuthorId, GenreId, GroupId, SeriesId, Isbn, LanguageCode, Cover, BookFile, BookMetadata)

text

### Application
application
├── dto (BookDto)
├── event (BookImportedEvent, ImportFinishedEvent, ImportSummary)
├── imports (розбито на підпакети)
│ ├── context (ImportContext)
│ ├── detector (BookFormatDetector)
│ ├── duplicate (DuplicateDetector, DuplicatePolicy)
│ ├── error (ImportErrorHandler)
│ ├── saver (BookSaver)
│ ├── scanner (LibraryScanner)
│ ├── statistics (ImportResult, ImportStatistics)
│ └── transaction (ImportTransaction)
├── port
│ └── out
│ ├── cache (Cache)
│ ├── cover (CoverCache, CoverExtractor, CoverReader, CoverLocator, ArchiveReader)
│ ├── event (EventPublisher)
│ ├── importer (BookImporterPort, ImporterRegistry)
│ ├── repository(AuthorRepository, BookCommandRepository, BookQueryRepository, GenreRepository, GroupRepository, SeriesRepository)
│ └── search (IndexRebuilder, SearchIndexer, SearchQueryService)
├── query (розбито на підпакети)
│ ├── book (BookQuery, BookFormat)
│ ├── common (Pagination, SortBy, SortDirection)
│ └── search (SearchRequest, SearchResult, SearchMode)
└── usecase (реалізація сценаріїв)
├── book (LoadBooksUseCase)
├── genre (LoadGenresUseCase)
├── group (CreateGroupUseCase, RenameGroupUseCase, DeleteGroupUseCase, AddBookToGroupUseCase, RemoveBookFromGroupUseCase)
├── imports (ImportDirectoryUseCase, ImportFileUseCase)
├── index (RebuildIndexUseCase)
└── search (SearchBooksUseCase)

text

**Примітка:** у `application` відсутні пакети `service` та `mapper`. Уся бізнес-логіка виконується через `usecase`. Мапери для UI знаходяться в `ui.mapper` (або `infrastructure.mapper` для персистентності).

### Infrastructure
infrastructure
├── cache (BookCache, BookCacheEvictor, CachedBookQueryRepository, CacheFactory, CaffeineCache, CoverCache)
├── config (AsyncConfig, CacheConfig, DatabaseConfig)
├── cover (ZipArchiveReader, CoverLocatorImpl, CoverReaderImpl)
├── event (SimpleEventBus, різні EventHandler-и)
├── image (Fb2CoverParser, ImageLoader) – застарілий, замінено на cover
├── importer (AbstractBookImporter, DefaultImporterRegistry, Fb2Importer, InpxImporter, ZipImporter)
├── parser (Fb2AnnotationParser, Fb2AuthorParser, Fb2GenreParser, Fb2KeywordsParser, Fb2LanguageParser, Fb2ParserContext, Fb2SequenceParser, Fb2TitleParser)
├── persistence
│ ├── mapper (AuthorRowMapper, BookRowMapper, BookMapper, GenreRowMapper, GroupRowMapper, SeriesRowMapper)
│ ├── postgres (PostgresAuthorRepository, PostgresBookRepository) – заглушки
│ └── sqlite
│ ├── helper (BookAuthorHelper, BookGenreHelper, BookQueryBuilder)
│ ├── query (AuthorQueries, BookQueries, BookAuthorQueries, BookGenreQueries, GenreQueries, GroupQueries, SeriesQueries)
│ └── реалізації репозиторіїв (SqliteAuthorRepository, SqliteBookCommandRepository, SqliteBookQueryRepository, SqliteGroupRepository, SqliteSeriesRepository)
├── search (LuceneIndexRebuilder, LuceneSearchIndexer, LuceneSearchQueryService, NGramAnalyzer, SearchDocument, SearchIndexConfig) – NGramAnalyzer замінено на StandardAnalyzer
└── service (CoverService, GenreServiceImpl, GroupServiceImpl, SeriesServiceImpl) – Service замінено на репозиторії

text

### UI
ui
├── components (BookInfoPanel)
├── controller (MainController, DetailsController, NavigationController)
├── model (navigation) – перенесено з domain
├── presentation (BookDetailsPresenter)
├── presenter (BookImportPresenter, BookSearchPresenter, CoverPresenter, LibraryNavigationPresenter, ProgressPresenter, StatusBarPresenter)
├── service (BackgroundExecutor, BookSelectionService, BookTableService, DialogService)
├── util (UiExecutor)
└── viewmodel (MainViewModel, NavigationViewModel)

text

---

## 4. Виконаний рефакторинг (станом на 03.07.2026)

| № | Завдання | Статус |
|---|----------|--------|
| 1 | Розділити `application.port.out` на пакети (repository, importer, search, cover, cache, event) | ✅ |
| 2 | Замінити `GenreService`, `SeriesService` на репозиторії (`GenreRepository`, `SeriesRepository`) | ✅ |
| 3 | Перенести `navigation` з domain у `ui.model.navigation` | ✅ |
| 4 | Об'єднати `ImporterRegistry` та `ImporterResolver` | ✅ |
| 5 | Розбити `application.imports` на підпакети | ✅ |
| 6 | Створити UseCase для завантаження даних з UI (автори, серії, жанри, групи) | ✅ |
| 7 | Розділити `application.query` на пакети (book, search, common) | ✅ |
| 8 | Додати архітектурні тести (LayerArchitectureTest) | ✅ |
| 9 | Виправити пошук (Lucene): перехід на `StandardAnalyzer`, пакетний коміт | ✅ |
| 10 | Виправити завантаження обкладинок: розділити на `ArchiveReader`, `CoverLocator`, `CoverReader`, оновити `Fb2CoverParser` | ✅ |
| 11 | Додати обробку кодувань ZIP (CP866, Windows-1251, KOI8-R) | ✅ |
| 12 | Видалити `NavigationManager` та `SearchManager` (функціонал перенесено в презентери) | ✅ |
| 13 | Оновити `MainController` (видалити дублювання слухачів) | ✅ |
| 14 | Додати конфігурацію `app.import.batch-size` | ✅ |
| 15 | Додати метод `commit()` до `SearchIndexer` | ✅ |

---

## 5. Що залишилося зробити (пріоритетні завдання)

| № | Завдання | Пріоритет |
|---|----------|-----------|
| 1 | Повністю розділити `SqliteBookRepository` на окремі класи для команд та запитів (вже є інтерфейси, але реалізація ще об'єднана) | 🔴 Високий |
| 2 | Створити окремі `BookSql`, `AuthorSql`, `GenreSql` тощо (SQL винесено в `query` пакет, але не скрізь) | 🔴 Високий |
| 3 | Додати кешування для авторів, жанрів, серій (`AuthorCache`, `GenreCache`, `SeriesCache`) | 🟠 Середній |
| 4 | Розширити систему подій (`BookAddedEvent`, `BookDeletedEvent`, `BookUpdatedEvent` тощо) | 🟠 Середній |
| 5 | Створити `BookViewModel` та `BookViewModelMapper` для UI | 🟠 Середній |
| 6 | Винести всі діалоги та повідомлення в `DialogService` (вже частково) | 🟠 Середній |
| 7 | Створити єдиний `BackgroundTaskService` замість окремих `ExecutorService` | 🟠 Середній |
| 8 | Розбити `MainController` на функціональні області (таблиця, пошук, імпорт тощо) | 🟠 Середній |
| 9 | Додати інтеграційні тести для міграцій Flyway | 🟡 Низький |
| 10 | Підготувати інтерфейси для плагінів (Plugin API) | 🟢 Майбутнє |
| 11 | Реалізувати OPDS-клієнт/сервер | 🟢 Майбутнє |
| 12 | Додати синхронізацію (Sync Engine) | 🟢 Майбутнє |

---

## 6. Основні принципи кодування (актуальні)

- **Domain не залежить від Spring, JavaFX, SQLite, Lucene.**
- **UI не працює з репозиторіями напряму** – тільки через UseCase.
- **Repository бувають двох типів:** Command (зміна) та Query (читання).
- **SQL винесено в окремі класи** (`*Queries`).
- **RowMapper винесено** в окремий пакет `persistence.mapper`.
- **Великі класи розбиваються** за відповідальністю (приклад: `CoverExtractor` розділено на `ArchiveReader`, `CoverLocator`, `CoverReader`).
- **Усі зовнішні залежності проходять через порти** (`application.port.out`).
- **У `application` немає пакетів `service` та `mapper`** – бізнес-логіка реалізується через UseCase, мапери для UI знаходяться у `ui.mapper`, для персистентності – у `infrastructure.persistence.mapper`.
- **UseCase не є Port** – UseCase реалізують сценарії, Port – це контракти для Infrastructure.
- **Bootstrap – єдиний модуль, який знає про всі інші модулі**. Жоден інший модуль не залежить від Bootstrap.
- **Domain Service не має залежності від Infrastructure** (не використовує JdbcTemplate, Spring тощо).
- **Policy не має доступу до Repository** – працює лише з Domain Model.
- **`SearchDocument` – це DTO для Lucene**, не є Domain Model і не використовується поза Search Layer.
- **Repository повертає Aggregate Root**, не містить бізнес-логіки та відповідає за ефективне відновлення Aggregate.

---

## 7. Подальший план рефакторингу (деталі)

### Етап 1 (найближчий): завершення репозиторіїв
- [ ] Остаточно розділити `SqliteBookRepository` на `SqliteBookCommandRepository` та `SqliteBookQueryRepository` (вже є, але деякі методи ще дублюються).
- [ ] Винести всі SQL-запити в окремі класи (вже частково).
- [ ] Додати `BatchInserter`, `BatchUpdater`.

### Етап 2: кешування та події
- [ ] Додати `AuthorCache`, `GenreCache`, `SeriesCache`.
- [ ] Розширити події для всіх змін (BookAdded, BookDeleted, BookUpdated).

### Етап 3: UI рефакторинг
- [ ] Створити `BookViewModel` та `BookViewModelMapper`.
- [ ] Винести всі діалоги в `DialogService`.
- [ ] Створити `BackgroundTaskService`.
- [ ] Розбити `MainController` на окремі презентери/сервіси.

### Етап 4: тести та документація
- [ ] Додати юніт-тести для критичних UseCase.
- [ ] Додати інтеграційні тести для репозиторіїв.

---

## 8. Архітектурні тести (ArchUnit)

У проєкті є окремий модуль `myhomelib-architecture-tests`, який містить набір правил для автоматичної перевірки архітектури. Основні правила, які перевіряються:

- **Domain не залежить від Application, Infrastructure, UI, Spring, JavaFX, JDBC**.
- **Application не залежить від Infrastructure, UI**.
- **Infrastructure не залежить від UI, JavaFX**.
- **UI не залежить від Infrastructure, Repository, Persistence, JDBC, SQLite**.
- **Усі класи в `application.port.out` є інтерфейсами**.
- **Шарова архітектура** (UI → Application → Domain ← Infrastructure) дотримується.

Ці тести запускаються разом із `mvn test` і допомагають підтримувати чистоту архітектури при подальшому розвитку.

---

## 9. Висновок

На сьогоднішній день проєкт має чітку багатошарову архітектуру з ізольованими доменом, сценаріями, інфраструктурою та інтерфейсом. 
Виконано значний обсяг рефакторингу, що дозволяє масштабувати систему до 1 000 000+ книг та готує основу для майбутніх клієнтів 
(Web, Android) і функцій (синхронізація, плагіни, OPDS). Залишилося довести до кінця рефакторинг репозиторіїв та UI,
 щоб повністю усунути технічний борг.

**Дата останнього оновлення:** 03.07.2026