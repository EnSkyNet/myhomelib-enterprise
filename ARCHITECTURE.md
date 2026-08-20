# ARCHITECTURE.md

# MyHomeLib Enterprise — архітектура поточного проекту

**Стан документа:** 19.08.2026  
**Версія проекту:** `1.0.0-SNAPSHOT`  
**Java:** 21  
**UI:** JavaFX 21.0.2 + JavaFX WebView  
**Backend/Application runtime:** Spring Boot 3.5.0  
**Модульна модель:** Maven multi-module, модульний моноліт  
**Основна БД:** SQLite  
**Міграції:** Flyway  
**Пошук:** Apache Lucene  
**Кеш:** Caffeine  
**FB2:** власний parser/renderer на Jsoup + HTML у JavaFX WebView

---

## 1. Призначення проекту

MyHomeLib Enterprise — сучасний Java-порт проекту MyHomeLib, початково реалізованого на Delphi/Pascal.

Поточна реалізація є настільним застосунком для керування локальною електронною бібліотекою з такими основними підсистемами:

- бібліотека книг;
- автори;
- жанри;
- серії;
- групи;
- колекції;
- пошук;
- імпорт FB2/INPX/ZIP;
- експорт;
- синхронізація каталогів;
- обкладинки;
- кешування;
- перевірка цілісності;
- статистика;
- закладки;
- збереження прогресу читання;
- повноцінний FB2 Reader;
- налаштування шрифту, розміру, теми, масштабу та режиму читання;
- зміст книги;
- автоскрол;
- нотатки/footnotes;
- внутрішні та зовнішні посилання.

Проект не є класичним web backend. Spring Boot використовується як контейнер залежностей, конфігураційна та сервісна платформа для desktop-застосунку, а JavaFX є основним UI runtime.

---

# 2. Загальна архітектурна модель

Поточна система є **модульним монолітом із принципами Hexagonal / Ports & Adapters Architecture**.

Верхньорівнева схема:

```text
                         ┌──────────────────────────┐
                         │      Bootstrap           │
                         │   MyHomeLibApp.java      │
                         │   Spring Boot runtime    │
                         └────────────┬─────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────┐
│                              UI                                     │
│                        myhomelib-ui                                 │
│                                                                     │
│ JavaFX Controllers / ViewModels / Presenters / Reader / WebView    │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          Application                                │
│                    myhomelib-application                            │
│                                                                     │
│ Use Cases / Application Services / DTO / Queries / Ports           │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                            Domain                                   │
│                       myhomelib-domain                              │
│                                                                     │
│ Entities / Value Objects / Domain Events                            │
└─────────────────────────────────────────────────────────────────────┘
                               ▲
                               │
                               │ implements ports
                               │
┌──────────────────────────────┴──────────────────────────────────────┐
│                        Infrastructure                               │
│                   myhomelib-infrastructure                          │
│                                                                     │
│ SQLite / Flyway / Lucene / Cache / Import / Export / Files / etc.  │
└─────────────────────────────────────────────────────────────────────┘

                         Shared
                           │
                           ▼
                 cross-cutting primitives
```

Це **не чиста багатошарова архітектура** і не повністю ізольована Hexagonal Architecture. Це практичний гібрид:

```text
Domain
   ↑
Application
   ↑
Infrastructure

UI
   ↓
Application
   ↓
Domain

UI також має пряму runtime-залежність від Infrastructure
через поточну Maven-модель.
```

Тому при подальшій розробці потрібно розрізняти:

1. архітектурні принципи, які вже реально виконуються;
2. архітектурні правила, які контролюються ArchUnit;
3. поточні Maven-залежності, які ще не доведені до ідеальної ізоляції.

---

# 3. Maven-модулі

Root `pom.xml` містить дев'ять модулів:

```text
myhomelib-shared
myhomelib-domain
myhomelib-application
myhomelib-infrastructure
myhomelib-ui
myhomelib-bootstrap
myhomelib-architecture-tests
myhomelib-e2e-tests
myhomelib-benchmark
```

## 3.1. myhomelib-shared

Призначення — мінімальні cross-cutting компоненти.

Поточний вміст:

```text
shared/
├── event/
│   ├── BaseDomainEvent
│   ├── DomainEvent
│   └── DomainEventPublisher
├── exception/
│   ├── BusinessException
│   └── ErrorCode
└── util/
    └── EncryptionUtil
```

Модуль не повинен перетворюватися на загальну папку для будь-якого коду.

Його роль — лише базові примітиви, які справді використовуються кількома внутрішніми модулями.

---

# 4. Domain

Модуль:

```text
myhomelib-domain
```

містить бізнес-модель системи.

Поточна модель включає:

```text
Author
Book
BookSnapshot
Bookmark
Collection
CollectionType
Genre
Group
Publisher
ReaderPreferences
SavedSearch
Series
SyncState
```

Також присутні Value Objects:

```text
BookId
AuthorId
CollectionId
GroupId
GenreId
PublisherId
SeriesId
...
```

та domain events:

```text
BookAddedEvent
BookDeletedEvent
BookUpdatedEvent
CollectionOpenedEvent
```

## 4.1. Правило Domain

Domain не повинен знати про:

- Spring;
- JavaFX;
- SQLite;
- JDBC;
- Lucene;
- UI;
- Infrastructure;
- файлову систему.

Поточний ArchUnit-тест це явно контролює.

Основний принцип:

```text
Domain = бізнес-модель, незалежна від способу зберігання та UI.
```

---

# 5. Application

Модуль:

```text
myhomelib-application
```

є центром application logic.

Він містить:

```text
DTO
Use Cases
Application Services
Queries
Ports
Mappers
Import orchestration
Search contracts
Statistics contracts
Session services
```

Основні групи use case:

```text
author
book
collection
dashboard
export
group
imports
integrity
navigation
search
series
sync
```

## 5.1. Book use cases

Поточна система має окремі use cases для:

```text
LoadBookByIdUseCase
LoadBooksByAuthorUseCase
LoadBooksUseCase
MarkAsReadBatchUseCase
UpdateProgressBatchUseCase
UpdateRateBatchUseCase
```

## 5.2. Collection use cases

```text
AddBookToCollectionUseCase
CreateCollectionUseCase
DeleteCollectionUseCase
IsBookInCollectionUseCase
LoadCollectionBooksUseCase
LoadCollectionsUseCase
RemoveBookFromCollectionUseCase
RenameCollectionUseCase
SwitchCollectionUseCase
```

## 5.3. Group use cases

Є окремі use cases для створення, видалення, перейменування, завантаження та batch-операцій.

## 5.4. Search

Application визначає абстракції:

```text
SearchQueryService
SearchIndexer
IndexRebuilder
SearchService
SearchRequest
SearchResult
SearchMode
```

Application не повинен знати, що фактична реалізація використовує Lucene.

---

# 6. Application Ports

Ports розташовані переважно в:

```text
com.myhomelibcorp.application.port.out
```

Поточні категорії:

```text
backup
cache
cover
event
executor
exporter
importer
infrastructure
integrity
reader
repository
resource
search
statistics
validation
```

Це є основним механізмом інверсії залежностей.

Наприклад:

```text
Application:
    ReadingProgressRepository
              │
              │ implements
              ▼
Infrastructure:
    SqliteReadingProgressRepository
```

Аналогічно:

```text
ReaderBookResourcePort
        │
        ▼
ReaderBookResourceAdapter
```

---

# 7. Infrastructure

Модуль:

```text
myhomelib-infrastructure
```

містить конкретні технічні реалізації.

Основні підсистеми:

```text
adapter
cache
cleanup
collection
config
cover
event
executor
exporter
image
importengine
importer
initializer
integrity
monitoring
parser
persistence
profiling
reader
resource
search
service
sync
util
warmup
```

---

# 8. Persistence

Основне постійне сховище — SQLite.

Реалізації репозиторіїв:

```text
SqliteAuthorRepository
SqliteBookCommandRepository
SqliteBookQueryRepository
SqliteBookmarkRepository
SqliteCollectionRepository
SqliteDuplicateBookLookup
SqliteGroupRepository
SqlitePublisherRepository
SqliteReadingProgressRepository
SqliteReadingStatisticsRepository
SqliteSavedSearchRepository
SqliteSeriesRepository
SqliteSessionRepository
SqliteStatisticsRepository
```

Також присутні PostgreSQL-реалізації:

```text
PostgresAuthorRepository
PostgresBookRepository
```

але поточний desktop runtime орієнтований на SQLite.

---

# 9. SQLite

SQLite використовується як основна локальна БД.

Технічний стек:

```text
Xerial SQLite JDBC
Flyway
JDBC
```

Для складніших запитів присутні:

```text
QueryExecutor
BookQueryBuilder
BookQueries
RowMappers
Batch writers
```

Це дозволяє не змішувати SQL безпосередньо з UI.

---

# 10. Flyway

Міграції знаходяться:

```text
myhomelib-infrastructure/src/main/resources/db/migration
```

Поточна історія містить:

```text
V1 ... V26
```

Серед них:

```text
V1__init.sql
V8__migrate_covers_to_fs.sql
V9__create_telemetry_table.sql
V10__add_library_format_version.sql
V11__create_series_table.sql
V13__create_session_table.sql
V16__create_reading_progress.sql
V23__create_bookmarks_table.sql
V24__create_reading_stats.sql
V25__update_reading_progress.sql
V26__recreate_reading_progress.sql
```

Також існує окремий `migration_meta`.

## 10.1. Важливе правило

Вже виконані Flyway migration не редагуються.

Будь-яка зміна схеми повинна створювати нову migration:

```text
V27__...
V28__...
```

і так далі.

---

# 11. Поточна модель reading_progress

Поточна таблиця після V26 має поля:

```text
book_id
paragraph_id
char_offset
percent
chapter_title
chapter_id
updated_at
reading_time_seconds
```

Основний ключ:

```text
book_id
```

Поточна реалізація зберігає позицію як:

```text
paragraph_id / XPath
+
char_offset
+
percent
```

Це працююча, але ще не повністю стабільна модель locator.

У поточній реалізації `paragraph_id` може використовуватися як XPath або як стабільний ID, що видно з логіки `ReaderPositionService`.

---

# 12. Cache

Кешування реалізовано через Caffeine.

Основні компоненти:

```text
BookCache
BookCacheEvictor
CacheFactory
CaffeineCache
CaffeineAuthorCache
CaffeineGenreCache
CaffeineSeriesCache
CaffeineSearchCache
CaffeineCoverCache
DictionaryCache
```

Також є cached repositories:

```text
CachedAuthorRepository
CachedBookQueryRepository
CachedGenreRepository
CachedSeriesRepository
```

Архітектурний принцип:

```text
Repository
    ↓
Cache adapter / cached repository
    ↓
actual persistence
```

Кеш не повинен ставати джерелом істини.

---

# 13. Search

Пошук реалізований на Apache Lucene.

Основні компоненти:

```text
LuceneSearchService
SearchIndexConfig
SearchIndexEventHandler
```

Application працює через:

```text
SearchQueryService
SearchIndexer
IndexRebuilder
```

Таким чином Lucene залишається infrastructure detail.

Пошук підтримується через окремі application query objects:

```text
SearchRequest
SearchResult
SearchMode
```

---

# 14. Import subsystem

Імпорт є однією з найбільших підсистем.

Підтримуються:

```text
FB2
INPX
ZIP
```

Основні infrastructure-компоненти:

```text
InpxImportPipeline
InpxReader
JdbcBatchWriter

AbstractBookImporter
DefaultImporterRegistry

Fb2Importer
InpxImporter
InpxFastImportService

Fb2ImportReader
InpxImportReader

ZipImporter
```

Application orchestration використовує:

```text
ImportDirectoryUseCase
ImportFileUseCase
```

і відповідні ports.

---

# 15. FB2

FB2 має два різні шляхи в поточній архітектурі.

## 15.1. Library import path

Для імпорту FB2 використовується:

```text
infrastructure.importer.fb2.Fb2Importer
```

та пов'язані importer/readers.

## 15.2. Reader path

Для безпосереднього читання книги використовується окрема Reader-підсистема:

```text
reader.parser.JsoupFb2Parser
reader.renderer.DocumentToHtmlConverter
```

Це важливе розділення.

Import відповідає за внесення книги в бібліотеку.

Reader відповідає за підготовку книги до інтерактивного читання.

---

# 16. Reader

Reader знаходиться переважно в:

```text
myhomelib-ui/src/main/java/com/myhomelibcorp/reader
```

Це свідомо окрема підсистема всередині UI-модуля.

Поточна структура:

```text
reader/
├── core/
│   └── ReaderSettings
├── model/
│   ├── BookDocument
│   ├── BookMetadata
│   ├── Chapter
│   ├── ImageData
│   ├── ReaderBookContent
│   ├── ReaderPosition
│   ├── ReaderReadingStats
│   └── ReaderTheme
├── parser/
│   └── JsoupFb2Parser
├── renderer/
│   └── DocumentToHtmlConverter
├── service/
│   ├── AutoScrollService
│   ├── ImageCacheService
│   ├── ReaderBookmarkService
│   ├── ReaderContentService
│   ├── ReaderFacade
│   ├── ReaderJsBridge
│   ├── ReaderPositionService
│   ├── ReaderScheduler
│   ├── ReaderSettingsService
│   ├── ReaderStatsService
│   └── ReaderTocService
└── session/
    ├── ReaderSession
    └── ReaderSessionManager
```

---

# 17. Reader — модель даних

`BookDocument` є проміжною моделлю між FB2 XML та HTML.

Схема:

```text
FB2
 ↓
JsoupFb2Parser
 ↓
BookDocument
 ├── BookMetadata
 ├── Chapter[]
 └── ImageData[]
 ↓
DocumentToHtmlConverter
 ↓
HTML
 ↓
JavaFX WebView
```

Це правильне концептуальне розділення.

Parser не повинен напряму керувати WebView.

Renderer не повинен читати SQLite.

---

# 18. JsoupFb2Parser

Поточний parser:

```text
JsoupFb2Parser
```

використовує Jsoup XML parser.

Він:

1. читає FB2;
2. визначає кодування;
3. знаходить `FictionBook`;
4. читає metadata;
5. читає authors;
6. читає images;
7. читає footnotes;
8. будує chapters;
9. будує paragraphs;
10. повертає `BookDocument`.

У parser також присутня поточна логіка генерації paragraph identifiers та XPath-related metadata.

Це місце є важливим для подальшої стабілізації reading position.

---

# 19. DocumentToHtmlConverter

`DocumentToHtmlConverter` перетворює:

```text
BookDocument
```

у:

```text
HTML document
```

HTML містить:

```text
DOCTYPE
html
head
meta charset
viewport
CSS
body
book metadata
chapters
paragraphs
images
footnotes
links
```

Для санітизації використовується Jsoup `Safelist`.

Підтримуються HTML-теги для:

```text
b
i
strong
em
u
s
sub
sup
code
pre
blockquote
q
ul
ol
li
hr
img
a
```

та відповідні структурні `div`, `span`, `p`, heading тощо.

---

# 20. Reader WebView

Поточний Reader використовує:

```text
JavaFX WebView
JavaFX WebEngine
```

WebView створюється програмно в:

```text
ReaderWorkspaceController.createWebView()
```

Він:

- вбудований у `StackPane`;
- отримує HTML через `WebEngine`;
- має JavaScript enabled;
- використовується для layout тексту;
- використовується для scroll;
- використовується для page mode;
- використовується для пошуку;
- використовується для внутрішньої навігації.

Це означає, що Reader є гібридним:

```text
Java:
  lifecycle
  state
  persistence
  settings
  services

JavaScript:
  DOM
  layout
  scroll
  viewport
  text coordinates
```

---

# 21. ReaderFacade

`ReaderFacade` є головним orchestration service Reader.

Він координує:

```text
LoadBookByIdUseCase
SessionService
ReaderSessionManager
ReaderContentService
ReaderPositionService
ReaderBookmarkService
ReaderTocService
ReaderSettingsService
ReaderStatsService
ReaderScheduler
```

Основні операції:

```text
openBook()
closeBook()
loadBookContent()
saveCurrentPosition()
schedulePositionSave()
savePositionNow()
restorePositionAfterLoad()
getCurrentPosition()
addBookmark()
removeBookmark()
getBookmarks()
goToBookmark()
getToc()
navigateToChapter()
getCurrentChapterTitle()
settings
zoom
cache
statistics
```

Facade є правильною точкою orchestration, щоб `ReaderWorkspaceController` не керував усіма Reader-сервісами безпосередньо.

---

# 22. ReaderSession

`ReaderSession` представляє активне читання конкретної книги.

Вона зберігає runtime state:

```text
book
bookId
sessionId
WebView
WebEngine
progress
zoom
restore position
UI references
active/closed state
```

`ReaderSessionManager` керує поточною Reader session.

Поточна модель орієнтована на одну активну книгу одночасно.

---

# 23. Reader position

Основні компоненти:

```text
ReaderPosition
ReaderPositionService
SqliteReadingProgressRepository
```

Поточний flow:

```text
WebView
 ↓
ReaderPositionService.getCurrentPosition()
 ↓
ReaderPosition
 ↓
ReadingProgressDto
 ↓
ReadingProgressRepository
 ↓
SQLite
```

При повторному відкритті:

```text
SQLite
 ↓
ReadingProgressDto
 ↓
ReaderPosition
 ↓
ReaderSession.restorePosition
 ↓
ReaderPositionService.restorePosition()
 ↓
JavaScript
 ↓
WebView scroll
```

---

# 24. Поточний механізм визначення позиції

Поточний `ReaderPositionService` визначає позицію на основі DOM viewport.

Він аналізує:

```text
scrollTop
window.innerHeight
document height
paragraph rects
visible paragraph
paragraphId
xpath
paragraphIndex
charOffset
percent
chapterTitle
```

Однак `charOffset` наразі обчислюється приблизно через співвідношення видимої висоти paragraph до його повної висоти.

Це не є точним текстовим locator.

---

# 25. Поточний механізм відновлення позиції

Поточний restore працює так:

```text
saved xpath / paragraphId
        ↓
querySelector()
        ↓
paragraph
        ↓
scrollIntoView({ block: 'start' })
        ↓
за наявності charOffset
створюється DOM Range
        ↓
Range встановлюється як Selection
```

Якщо paragraph не знайдено:

```text
percent
 ↓
scrollTo()
```

Таким чином зараз існує три рівні locator:

```text
XPath
paragraphId
percent
```

а `charOffset` використовується після пошуку paragraph.

---

# 26. Поточна проблема reading position

У поточній версії є технічні проблеми, які потрібно враховувати при подальшій розробці.

## 26.1. Selection використовується як частина position logic

JavaScript position tracking має код, який працює з:

```text
window.getSelection()
```

При цьому restore також створює Selection.

Selection семантично є виділенням тексту, а не reading cursor.

Це створює ризик, що відновлення та подальше збереження позиції можуть взаємно впливати одне на одного.

## 26.2. Restore не використовує charOffset для точного scroll

Поточний restore спочатку:

```text
paragraph.scrollIntoView()
```

а потім створює Range на `charOffset`.

Сам Range не використовується для корекції фактичного scroll.

Отже позиція може відновитися на початку paragraph, а не на точному місці всередині нього.

## 26.3. `data-xpath` не входить до Safelist для paragraph

`DocumentToHtmlConverter` дозволяє:

```text
data-paragraph-id
```

але поточний Safelist для `p` не містить:

```text
data-xpath
```

При проходженні через `Jsoup.clean()` цей атрибут може бути видалений.

Водночас `ReaderPositionService` очікує:

```text
p[data-xpath="..."]
```

Це створює невідповідність між генерацією HTML і restore logic.

## 26.4. `paragraphId` генерується порядково

Поточний parser має:

```text
paragraphCounter
```

і генерує paragraph IDs залежно від порядку обробки.

Такий ID не є повністю стабільною ідентичністю текстового вузла.

## 26.5. `savePositionNow()` фактично асинхронний

Метод запускає отримання позиції через FX scheduler.

Тому його назва створює хибне очікування, що після повернення методу позиція вже гарантовано записана.

Це особливо важливо при закритті session.

---

# 27. Reader settings

Reader підтримує:

```text
ReaderSettings
ReaderSettingsService
ReaderTheme
```

Поточна функціональність включає:

```text
font family
font size
theme
zoom
page mode
auto-scroll
scroll speed
```

Налаштування застосовуються через `ReaderContentService`.

---

# 28. Bookmarks

Закладки реалізовані окремою підсистемою:

```text
ReaderBookmarkService
BookmarksController
BookmarkRepository
```

Закладка є persistent даними, а Reader service відповідає за navigation до неї.

---

# 29. TOC

Зміст книги обробляється:

```text
ReaderTocService
TOCController
Chapter
```

TOC формується з Reader document/chapter structure.

Навігація:

```text
TOCController
 ↓
ReaderFacade
 ↓
ReaderTocService
 ↓
WebView
```

---

# 30. Auto-scroll

Автоскрол реалізований:

```text
AutoScrollService
```

та керується через:

```text
ReaderWorkspaceController
ReaderSettings
```

Автоскрол є runtime функцією WebView Reader і не повинен містити persistence logic.

---

# 31. Reading statistics

Статистика читання розділена від reading position.

Є:

```text
ReaderReadingStats
ReaderStatsService
ReadingStatisticsDto
ReadingStatisticsPort
SqliteReadingStatisticsRepository
```

Це правильне розділення:

```text
reading_progress
    = де користувач читає

reading_stats
    = скільки/коли користувач читає
```

---

# 32. UI

Основний UI — JavaFX.

Є:

```text
FXML
Controllers
ViewModels
Presenters
Navigation
Workspace management
Dialogs
Services
```

Основні UI області:

```text
author
book
collection
dashboard
details
group
imports
navigation
reader
search
statusbar
table
```

---

# 33. Navigation

Основні компоненти:

```text
NavigationService
DefaultNavigationService
NavigationHistoryService
NavigationPanelController
WorkspaceManager
WorkspaceLifecycle
```

Workspace lifecycle використовується для керування переходами між робочими областями.

---

# 34. Author workspace

Основна сторінка автора реалізована через:

```text
AuthorWorkspaceController
author-workspace.fxml
```

Вона працює через application use cases/DTO і не повинна напряму звертатися до SQLite.

---

# 35. Search UI

Search UI:

```text
SearchWorkspaceController
SearchViewModel
BookSearchPresenter
```

Application layer визначає search contracts, infrastructure реалізує Lucene.

Це відповідає загальній схемі:

```text
UI
 ↓
Application search
 ↓
Search port
 ↓
Lucene infrastructure
```

---

# 36. Import UI

Import UI:

```text
ImportWorkspaceController
ImportController
BookImportPresenter
```

Викликає application use cases, а actual file processing виконується infrastructure.

---

# 37. Bootstrap

`myhomelib-bootstrap` є executable module.

Основний entry point:

```text
com.myhomelibcorp.MyHomeLibApp
```

Також є:

```text
LibraryHealthIndicator
```

Bootstrap підключає:

```text
Spring Boot
JavaFX
UI
Infrastructure
```

і запускає desktop application.

---

# 38. Spring Boot

Spring використовується як DI/container framework.

Основні ролі:

```text
@Service
@Component
@Configuration
@Bean
@Autowired через constructor injection
```

Spring не є частиною Domain model.

Domain залишається plain Java.

---

# 39. Spring Modulith

У root dependencies присутній:

```text
spring-modulith 1.4.0
```

але поточний проект **не використовує явні `@ApplicationModule`, `@NamedInterface` або `ApplicationModules` architectural declarations**.

Тому фактична модульність зараз визначається переважно:

```text
Maven modules
package structure
ArchUnit rules
dependency direction
```

а не Spring Modulith module boundaries.

---

# 40. Events

Domain/shared events:

```text
DomainEvent
DomainEventPublisher
BaseDomainEvent
```

Infrastructure реалізує event publishing через:

```text
SpringDomainEventPublisher
SimpleEventBus
```

Обробники включають:

```text
DomainBookEventHandler
CacheEvictor
SearchIndexEventHandler
StatisticsEventHandler
```

Типовий flow:

```text
Book change
 ↓
Domain event
 ↓
Event publisher
 ├── cache invalidation
 ├── search indexing
 └── statistics
```

Це зменшує пряме зв'язування основного CRUD flow із secondary effects.

---

# 41. Configuration

Infrastructure configuration включає:

```text
ApplicationServiceConfig
AsyncConfig
CacheConfig
CollectionTransactionConfig
DatabaseCleanupConfig
DataSourceConfig
FlywayMetadataConfig
MetadataDatabaseConfig
ReaderResourceConfig
```

Це означає, що technical wiring переважно знаходиться поза UI.

---

# 42. Async execution

Поточна система має:

```text
BackgroundExecutor
SpringExecutorAdapter
ReaderScheduler
UiBackgroundExecutor
BackgroundTaskService
UiExecutor
```

Є декілька execution abstractions через різні рівні системи.

Особливо важливо не плутати:

```text
background worker thread
JavaFX Application Thread
WebEngine JavaScript execution
```

Reader WebView повинен працювати з FX/WebEngine контекстом.

---

# 43. Threading model

Ключові потоки:

```text
JavaFX Application Thread
        │
        ├── UI
        ├── WebView
        └── WebEngine JavaScript

Background executors
        │
        ├── import
        ├── database work
        ├── indexing
        ├── heavy processing
        └── background tasks
```

Правило:

```text
JavaFX UI/WebView operations → FX thread
heavy IO/import/indexing → background thread
database operations → infrastructure
```

Порушення цього правила може призвести до нестабільності UI або WebEngine.

---

# 44. Architecture tests

Окремий Maven module:

```text
myhomelib-architecture-tests
```

містить:

```text
LayerArchitectureTest
```

Поточні правила контролюють:

## Domain

Не залежить від:

```text
application
infrastructure
ui
Spring
JavaFX
java.sql
```

## Application

Не залежить від:

```text
infrastructure
ui
JDBC
Lucene
JavaFX
```

## Infrastructure

Не залежить від:

```text
ui
```

## UI

Не залежить безпосередньо від:

```text
repository
persistence
jdbc
sqlite
application.port.out
```

Також контролюється відсутність прямої залежності UI від основних Domain models там, де існують DTO.

## Reader

Контролюється відсутність прямої залежності Reader від:

```text
infrastructure
sqlite
lucene
```

Окремо перевіряється:

```text
ReaderBookResourcePort
```

повинен мати infrastructure implementation.

---

# 45. Важлива особливість Maven-залежностей

Попри ArchUnit правила, поточний `myhomelib-ui/pom.xml` має dependency:

```text
myhomelib-infrastructure
```

Тобто на Maven-рівні UI та Infrastructure ще пов'язані.

Це означає:

```text
архітектурна логічна ізоляція ≠ повна фізична ізоляція Maven-модулів
```

ArchUnit контролює Java class dependencies у визначених областях, але не замінює Maven dependency graph.

На поточному етапі це слід вважати свідомим технічним станом, а не припускати, що UI вже повністю відокремлений від Infrastructure.

---

# 46. Tests

У поточному архіві є 8 Java test files.

Основні області:

```text
Domain:
LanguageCodeTest

Infrastructure:
InpxImportPipelineTest
Fb2ImporterTest
DatabaseTest
SqliteBookQueryRepositoryTest
TestCollectionManager
PerformanceProfilerTest

Architecture:
LayerArchitectureTest
```

Окремого повного Reader test suite у поточному стані немає.

Це важливий архітектурний gap, особливо для:

```text
reading position
restore
WebView integration
reader settings
bookmarks
TOC
```

---

# 47. E2E tests

Maven module:

```text
myhomelib-e2e-tests
```

присутній і має Testcontainers dependencies.

Але в поточному архіві немає Java test classes у цьому модулі.

Тобто E2E infrastructure підготовлена на рівні Maven, але фактичний E2E test suite наразі не сформований.

---

# 48. Benchmark

Є окремий модуль:

```text
myhomelib-benchmark
```

з:

```text
ImportBenchmark
```

Він призначений для вимірювання продуктивності import pipeline.

Benchmark не є частиною runtime application.

---

# 49. Resource organization

UI resources:

```text
myhomelib-ui/src/main/resources/view/
```

містять 25 FXML views.

Основні:

```text
MainView.fxml
dashboard.fxml
author-workspace.fxml
book-workspace.fxml
collection-workspace.fxml
groups-workspace.fxml
reader-workspace.fxml
reader-settings.fxml
search-workspace.fxml
import-workspace.fxml
details.fxml
toc-dialog.fxml
bookmark-dialog.fxml
```

Infrastructure resources містять:

```text
db/migration
db/migration_meta
genres_fb2.txt
```

Bootstrap resources:

```text
application.yml
application-dev.yml
application-prod.yml
```

---

# 50. Поточний dependency stack

Основні версії:

```text
Java                         21
Spring Boot                  3.5.0
Spring Modulith              1.4.0
JavaFX                       21.0.2
SQLite JDBC                  3.47.0.0
Flyway                       10.16.0
MapStruct                    1.5.5.Final
Lombok                       1.18.30
Caffeine                     3.1.8
Lucene                       9.9.1
JUnit                        5.10.2
Mockito                      5.10.0
AssertJ                      3.25.3
ArchUnit                     1.3.0
Testcontainers               1.19.7
```

---

# 51. Поточна структура проекту

```text
myhomelib-enterprise/
│
├── pom.xml
│
├── myhomelib-shared/
│   └── cross-cutting primitives
│
├── myhomelib-domain/
│   └── business model
│
├── myhomelib-application/
│   ├── dto
│   ├── event
│   ├── imports
│   ├── mapper
│   ├── port
│   ├── query
│   ├── search
│   ├── service
│   ├── session
│   ├── statistics
│   └── usecase
│
├── myhomelib-infrastructure/
│   ├── adapter
│   ├── cache
│   ├── config
│   ├── event
│   ├── executor
│   ├── exporter
│   ├── importengine
│   ├── importer
│   ├── parser
│   ├── persistence
│   ├── reader
│   ├── resource
│   ├── search
│   ├── sync
│   └── ...
│
├── myhomelib-ui/
│   ├── reader
│   ├── ui
│   └── resources/view
│
├── myhomelib-bootstrap/
│   ├── MyHomeLibApp
│   └── monitoring
│
├── myhomelib-architecture-tests/
│   └── LayerArchitectureTest
│
├── myhomelib-e2e-tests/
│
└── myhomelib-benchmark/
    └── ImportBenchmark
```

---

# 52. Основні runtime flows

## 52.1. Відкриття книги в бібліотеці

```text
UI
 ↓
LoadBookByIdUseCase
 ↓
BookQueryRepository
 ↓
SQLite
 ↓
BookDto
 ↓
UI
```

---

# 53. Відкриття книги в Reader

```text
ReaderWorkspaceController
        ↓
ReaderFacade.openBook()
        ↓
LoadBookByIdUseCase
        ↓
ReaderSessionManager.createSession()
        ↓
ReaderPositionService.loadPosition()
        ↓
SQLite
        ↓
ReaderSession.restorePosition
        ↓
ReaderFacade.loadBookContent()
        ↓
ReaderContentService
        ↓
FB2 resource
        ↓
JsoupFb2Parser
        ↓
BookDocument
        ↓
DocumentToHtmlConverter
        ↓
HTML
        ↓
WebEngine.loadContent()
        ↓
restorePosition()
        ↓
WebView
```

---

# 54. Збереження позиції

Поточний flow:

```text
WebView
 ↓
ReaderPositionService.getCurrentPosition()
 ↓
ReaderPosition
 ↓
debounce scheduler
 ↓
ReadingProgressDto
 ↓
ReadingProgressRepository
 ↓
SqliteReadingProgressRepository
 ↓
reading_progress
```

При закритті:

```text
ReaderWorkspaceController
        ↓
ReaderFacade.closeBook()
        ↓
savePositionNow()
        ↓
ReadingProgressRepository
        ↓
SQLite
        ↓
endReadingSession()
        ↓
close ReaderSession
```

У поточній реалізації Controller також викликає `savePositionNow()` перед `closeBook()`, що створює дублювання lifecycle logic.

---

# 55. Library import flow

```text
UI
 ↓
ImportFileUseCase / ImportDirectoryUseCase
 ↓
ImporterRegistry
 ↓
Fb2Importer / InpxImporter / ZipImporter
 ↓
Parser / Reader
 ↓
Book domain model / DTO
 ↓
BookCommandRepository
 ↓
SQLite
 ↓
Domain events
 ├── cache invalidation
 ├── search index update
 └── statistics
```

---

# 56. Search flow

```text
Search UI
 ↓
SearchWorkspaceController
 ↓
Search application service
 ↓
SearchQueryService
 ↓
LuceneSearchService
 ↓
Lucene index
 ↓
SearchResult
 ↓
ViewModel / Presenter
 ↓
JavaFX
```

---

# 57. Cache invalidation flow

```text
Book mutation
 ↓
Domain event
 ↓
SpringDomainEventPublisher
 ↓
CacheEvictor / SearchIndexEventHandler
 ↓
Caffeine cache invalidation
 ↓
Lucene index update
```

Це дозволяє не вставляти cache invalidation вручну в кожен UI flow.

---

# 58. Основні архітектурні принципи проекту

Поточний проект слід розвивати відповідно до таких правил:

## 58.1. Domain не знає про framework

Не додавати:

```text
Spring annotations
JavaFX classes
JDBC
SQLite
Lucene
```

у Domain.

## 58.2. Application визначає контракти

Якщо application потребує:

```text
database
filesystem
reader resource
search
cache
executor
```

спочатку створюється port.

## 58.3. Infrastructure реалізує ports

Concrete technology повинна залишатися Infrastructure detail.

## 58.4. UI працює через application/use cases

UI не повинен виконувати SQL.

## 58.5. Reader не працює напряму з persistence

Reader використовує:

```text
ReaderFacade
Application use cases
Reader ports
```

а не SQLite repository напряму.

## 58.6. WebView є rendering runtime, а не persistence layer

JavaScript не повинен самостійно зберігати дані в SQLite.

---

# 59. Поточні архітектурні слабкі місця

## 59.1. UI → Infrastructure Maven dependency

`myhomelib-ui` залежить від `myhomelib-infrastructure`.

Це робить фізичну модульну ізоляцію слабшою, ніж логічна.

## 59.2. Reader знаходиться всередині UI module

Reader містить значну кількість domain-independent logic:

```text
parser
renderer
position
session
content
settings
statistics
```

Тому з часом він може стати окремим application/UI boundary.

Але на поточному етапі виділяти Reader в окремий Maven module не обов'язково.

## 59.3. WebView JavaScript logic розподілена по сервісах

Частина DOM logic знаходиться в:

```text
ReaderPositionService
ReaderJsBridge
ReaderWorkspaceController
ReaderTocService
AutoScrollService
```

Це збільшує ризик дублювання JavaScript.

## 59.4. Reader position model ще не стабільна

Поточна модель змішує:

```text
paragraphId
xpath
paragraphIndex
charOffset
percent
```

без одного чіткого canonical locator.

## 59.5. Тестовий контур Reader недостатній

Reader має критичну persistence/rendering поведінку, але окремих автоматичних тестів для reopen/restore немає.

---

# 60. Що в архітектурі вже добре

Поточна структура має кілька сильних сторін.

### 1. Domain isolation

Domain реально відокремлений від JavaFX/Spring/SQL.

### 2. Application ports

Repository/search/resource/cache contracts винесені в application.

### 3. Infrastructure isolation

SQLite, Lucene, Caffeine, Flyway та import/export реалізовані окремо.

### 4. Reader facade

Reader orchestration не повністю знаходиться у JavaFX Controller.

### 5. Session model

Reader має явну `ReaderSession`.

### 6. Separate DTO layer

UI не повинен безпосередньо працювати з більшістю Domain entities.

### 7. Architecture tests

Проект вже має ArchUnit-захист від основних dependency violations.

### 8. Migration history

Database schema управляється Flyway, а не ручним створенням таблиць.

### 9. Event-driven secondary operations

Cache/search/statistics можуть реагувати на domain events.

---

# 61. Архітектурні правила для подальшого розвитку

Новий код потрібно розміщувати за такими правилами.

## Новий бізнес-об'єкт

```text
myhomelib-domain
```

## Новий бізнес use case

```text
myhomelib-application/usecase
```

## Новий application contract

```text
myhomelib-application/port
```

## SQLite implementation

```text
myhomelib-infrastructure/persistence/sqlite
```

## Lucene implementation

```text
myhomelib-infrastructure/search
```

## Cache implementation

```text
myhomelib-infrastructure/cache
```

## File/resource implementation

```text
myhomelib-infrastructure/resource
```

## Reader parser

```text
myhomelib-ui/reader/parser
```

## Reader rendering

```text
myhomelib-ui/reader/renderer
```

## Reader state/service

```text
myhomelib-ui/reader/service
```

## JavaFX Controller

```text
myhomelib-ui/ui
```

## FXML

```text
myhomelib-ui/src/main/resources/view
```

---

# 62. Правило для database changes

Не створювати SQL у:

```text
UI
Reader
Application use case
Domain
```

SQL повинен знаходитися в Infrastructure.

Schema changes:

```text
Flyway migration
```

Repository:

```text
Infrastructure
```

Port:

```text
Application
```

DTO:

```text
Application
```

---

# 63. Правило для Reader changes

Будь-яка нова Reader feature повинна спочатку визначити:

```text
runtime state
persistent state
UI state
```

Наприклад, для bookmark:

```text
persistent:
BookmarkRepository

application:
Bookmark contract/use case

reader:
ReaderBookmarkService

UI:
BookmarksController
```

Для reading position:

```text
persistent:
ReadingProgressRepository

application:
ReadingProgressDto / port

reader:
ReaderPositionService

UI:
ReaderWorkspaceController
```

---

# 64. Поточний статус Reader

Reader уже не є базовим FB2 viewer.

Він має:

```text
FB2 parsing
HTML rendering
images
footnotes
links
TOC
bookmarks
themes
font settings
font size
zoom
page mode
auto-scroll
reading progress
reading statistics
session management
search
```

Тому Reader слід розглядати як **окрему функціональну підсистему**, навіть якщо фізично він поки знаходиться у `myhomelib-ui`.

---

# 65. Що не слід робити при подальшій розробці

Не потрібно:

```text
додавати SQL у Controller;
додавати SQLite repository у Reader;
додавати Lucene у UI;
переносити бізнес-правила в FXML controller;
створювати дублікати repository;
створювати новий parser для кожної Reader feature;
зберігати reading state через WebView localStorage;
змішувати JavaFX state та persistent state;
додавати ще один execution framework;
створювати новий module без реальної потреби.
```

---

# 66. Принцип "один власник відповідальності"

Для критичних областей:

```text
Database schema
    → Flyway

Database access
    → Infrastructure repositories

Business orchestration
    → Application use cases/services

Reader orchestration
    → ReaderFacade

Reader position
    → ReaderPositionService

Reader session
    → ReaderSessionManager

HTML generation
    → DocumentToHtmlConverter

FB2 parsing
    → JsoupFb2Parser

JavaFX lifecycle
    → Controller / WorkspaceManager

Navigation
    → NavigationService / WorkspaceManager

Search implementation
    → LuceneSearchService

Cache implementation
    → Caffeine infrastructure
```

Не потрібно створювати другий компонент із тією самою відповідальністю.

---

# 67. Архітектурна оцінка поточного стану

Поточна система вже має основу, достатню для подальшої розробки без повного rewrite.

Оцінка поточного стану:

```text
Domain isolation             — добре
Application ports            — добре
Persistence separation       — добре
Import architecture           — добре
Search separation             — добре
Cache separation              — добре
Event infrastructure         — добре
Reader decomposition         — добре
Reader rendering              — добре
UI organization               — добре
Maven modularity              — добре
ArchUnit protection           — добре
E2E coverage                  — слабко
Reader test coverage          — слабко
Reader position model         — потребує стабілізації
UI/Infrastructure physical
separation                    — неповна
Spring Modulith enforcement   — фактично не використовується
```

---

# 68. Цільова еволюція без повного переписування

Поточну архітектуру не потрібно ламати.

Правильний напрямок:

```text
поточна структура
      ↓
стабілізація Reader position
      ↓
Reader tests
      ↓
зменшення дублювання UI lifecycle
      ↓
посилення application ports
      ↓
поступове зменшення UI → Infrastructure dependency
```

Не потрібно починати новий проект або переносити весь Reader в інший framework.

---

# 69. Головна архітектурна межа

Для подальшої роботи потрібно мислити проект такими блоками:

```text
                 BUSINESS
                    │
                    ▼
                 DOMAIN
                    │
                    ▼
               APPLICATION
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
      UI / Reader        Infrastructure
          │                   │
          └────── runtime ────┘
```

При цьому:

```text
Domain
```

є найстабільнішим шаром.

```text
Application
```

визначає бізнесові сценарії та контракти.

```text
Infrastructure
```

можна змінювати без зміни бізнес-моделі.

```text
UI/Reader
```

можна розвивати незалежно від persistence implementation.

---

# 70. Короткий architectural contract

Перед внесенням будь-якої нової функції потрібно відповісти на п'ять питань:

1. Це бізнес-правило чи UI-поведінка?
2. Чи потрібні persistent дані?
3. Хто є власником цього state?
4. Через який port проходить доступ до зовнішнього ресурсу?
5. Чи порушує новий код існуючі ArchUnit rules?

Якщо відповідь на п'яте питання — так, спочатку потрібно змінити dependency boundary, а не обходити правило.

---

# 71. Підсумкова схема

Поточна архітектура проекту:

```text
┌──────────────────────────────────────────────────────────────┐
│                        BOOTSTRAP                             │
│                    Spring Boot + JavaFX                      │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                            UI                                │
│                                                              │
│ Controllers / ViewModels / Presenters / Navigation           │
│                                                              │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │                         READER                           │ │
│ │                                                          │ │
│ │ Session → Content → Parser → Document → HTML → WebView │ │
│ │          Position → Persistence                         │ │
│ │          TOC / Bookmark / Settings / Stats              │ │
│ └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                       APPLICATION                            │
│                                                              │
│ Use Cases / DTO / Queries / Ports / Services / Events        │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                         DOMAIN                               │
│                                                              │
│ Book / Author / Genre / Series / Collection / Group / etc.  │
│ Value Objects / Domain Events                                │
└──────────────────────────────▲───────────────────────────────┘
                               │
                               │ implemented ports
                               │
┌──────────────────────────────┴───────────────────────────────┐
│                     INFRASTRUCTURE                            │
│                                                              │
│ SQLite / Flyway / Lucene / Caffeine / Files / Import /      │
│ Export / Sync / Events / Executors / Resources / Covers     │
└──────────────────────────────────────────────────────────────┘
```

---

# 72. Висновок

Поточний MyHomeLib Enterprise — це вже **модульний desktop application на Java 21 із Domain/Application/Infrastructure/UI розділенням**, а не набір контролерів і SQL-запитів.

Найбільш сформовані частини:

```text
Domain
Application ports
SQLite persistence
Import pipeline
Lucene search
Caffeine cache
Events
JavaFX navigation
Reader decomposition
```

Найважливіші технічні області, які зараз потребують стабілізації:

```text
Reader reading-position locator
Reader restore algorithm
Reader save/close lifecycle
Reader automated tests
UI → Infrastructure physical dependency
```

Критично важливо: **ця документація описує фактичний стан архіву станом на 19.08.2026, а не бажану майбутню архітектуру**. Запропоновані майбутні зміни не повинні трактуватися як уже реалізовані.

Для подальшої роботи базовою архітектурною одиницею залишається:

```text
Domain
    ↓
Application + Ports
    ↓
Infrastructure adapters
    ↓
UI / Reader
```

а Reader залишається окремою функціональною підсистемою всередині `myhomelib-ui`.
