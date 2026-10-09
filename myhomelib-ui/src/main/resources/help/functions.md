# MyHomeLib 8.0.0 — повний каталог користувацьких функцій

Дата аудиту: 2026-10-02
Поточний стан: V34 post-real-data source candidate; V30–V34 implementation завершена локально, зовнішні Windows/Kobo/Readwise/PWA gates позначені LIVE/WIN і не маскуються автоматичними тестами.
Призначення: канонічний опис функцій, доступних кінцевому користувачу desktop MyHomeLib.

## Як читати статус перевірки

- **E2E** — є end-to-end journey test через кілька шарів продукту.
- **AUTO** — є цільові автоматизовані behavioral/contract/integration тести.
- **WIRE** — перевірено існування UI entry point, controller handler/command wiring та відсутність розірваного FXML binding; це не прирівнюється до live UI execution.
- **LIVE** — потрібен реальний зовнішній сервіс/пристрій/ОС для повної приймальної перевірки.
- **WIN** — обов'язкова Windows x64 acceptance.

Машинний повний реєстр усіх FXML entry points і програмно створених action surfaces: `docs/current/UI-ACTION-COVERAGE.json`.

<!-- function-ref:main-navigation -->
## 1. Головне вікно, навігація та представлення

| Функція | Що робить | Основна точка входу | Перевірка |
|---|---|---|---|
| Головна / Dashboard | Показує загальний стан бібліотеки, останні/нові книги, Continue Reading та derived «Далі у серіях» без окремого persisted state | Library → Home | AUTO, WIRE |
| Назад / вперед | Повертає попередній або наступний workspace | toolbar, Alt+Left / Alt+Right | AUTO, WIRE |
| Глобальний пошук | Відкриває Search workspace за введеним запитом | toolbar, Ctrl+F | AUTO, E2E, WIRE |
| Очистити пошук | Скидає активний пошуковий запит | toolbar | WIRE |
| Оновити поточний workspace | Перезавантажує поточні дані без зміни контексту | View → Refresh, F5 | AUTO, WIRE |
| Ліва/права панелі | Показує або приховує navigation/details sidebars | View | AUTO, WIRE |
| Вибір колонок | Налаштовує видимі колонки для поточного табличного workspace | View → Choose columns | AUTO/WIRE залежно від workspace |
| Перемикання list/tree view | Перемикає класичне та деревоподібне представлення книг | More actions | WIRE |
| Недавні книги | Швидкий повторний перехід до нещодавно відкритих книг | View → Recent books | AUTO/WIRE |
| Мова UI | Перемикає bundled/external language catalog без перекомпіляції | View → Language | AUTO, WIRE |
| Тема застосунку | Light / Dark / AMOLED / custom theme | toolbar/settings | AUTO, WIRE |
| About | Інформація про застосунок | Help → About | WIRE |
| Вихід | Коректне завершення застосунку з lifecycle cleanup | File → Exit | AUTO/WIRE |

<!-- function-ref:catalog -->
## 2. Перегляд каталогу

| Функція | Що робить | Перевірка |
|---|---|---|
| Усі книги | Пагінований список усіх книг активної колекції | AUTO, WIRE |
| Автори | Навігація та пошук авторів; відкриття author workspace | AUTO, E2E, WIRE |
| Серії | Навігація за серіями з коректним sequence ordering | AUTO, WIRE |
| Жанри | Навігація за стабільними genre codes | AUTO, WIRE |
| Роки | Перегляд книг за роком | AUTO/WIRE |
| Мови | Перегляд книг за мовою | AUTO/WIRE |
| Видавці | Перегляд книг за видавцем | AUTO/WIRE |
| Архіви | Перегляд книг за архівом/контейнером | AUTO/WIRE |
| Ключові слова | Перегляд книг за keyword | AUTO/WIRE |
| Групи | Перегляд користувацьких груп та книг у них | AUTO, WIRE |
| Відгуки | Навігація за review subsets | AUTO/WIRE |
| Прочитані | Список прочитаних книг | AUTO/WIRE |
| Історія читання | Список reading-history | AUTO, WIRE |
| Нові книги | Перегляд нових книг каталогу | AUTO/WIRE |
| Алфавітна навігація | Швидка фільтрація/перехід за першою літерою | WIRE |
| Пагінація | Previous/Next, page size, bounded server-side pages | AUTO, WIRE |
| Quick filter | Швидкий фільтр у book table | AUTO, WIRE |
| Global filters | Розширена фільтрація списку книг | AUTO, WIRE |
| Table profile | Збереження/скидання параметрів таблиці | AUTO, WIRE |

<!-- function-ref:search -->
## 3. Пошук, saved searches та Smart Collections

| Функція | Що робить | Перевірка |
|---|---|---|
| Metadata search | Пошук за назвою, автором, серією, жанром, мовою, роком та іншими метаданими | AUTO, E2E |
| Content search | Повнотекстовий пошук у проіндексованому тексті книги | AUTO |
| Metadata + Contents | Об'єднаний Search Everywhere | AUTO |
| Highlight snippets | Виділяє знайдений фрагмент/контекст у результатах | AUTO |
| Авторські результати | Показує авторів у лівій navigation panel та відкриває author workspace | AUTO |
| Advanced fields | Фільтри title/author/series/genre/language/year/rating/file/date/local | AUTO, WIRE |
| Load more | Підвантажує наступні bounded pages | AUTO, WIRE |
| Saved search | Зберігає і повторно виконує пошуковий запит | AUTO, WIRE |
| Smart Collection | Створює типізований AND/OR rule set з bounded results | AUTO, WIRE |
| Smart Collection scope | Застосовує/очищає активний smart-collection scope | AUTO, WIRE |
| Content-index health | Виявляє stale/rebuild-required стан індексу | AUTO |

<!-- function-ref:collections -->
## 4. Колекції

| Функція | Що робить | Перевірка |
|---|---|---|
| Керування колекціями | Показує всі колекції та їх стан | AUTO, WIRE |
| Створити колекцію | Створює нову SQLite collection | AUTO, WIRE |
| Collection Wizard | Покроково створює local/online collection | AUTO/WIRE |
| Attach HLC2/SQLite | Безпечно підключає існуючу сумісну collection | AUTO |
| Активувати | Перемикає поточну collection із lifecycle quiesce старого state | AUTO, WIRE |
| Перейменувати | Змінює назву collection | AUTO/WIRE |
| Властивості | Редагує collection root/source/connectivity параметри | AUTO/WIRE |
| Видалити | Видаляє collection з cleanup залежних index/checkpoint state | AUTO |
| Source monitor | Зберігає/перевіряє source monitoring | AUTO, WIRE |
| Incoming folder | Вибір, збереження та сканування incoming folder | AUTO, WIRE |
| Update from INPX | Оновлює collection із локального INPX | AUTO |
| Update from network | Завантажує й застосовує remote catalogue revision | AUTO |
| Cancel/undo update | Скасовує активне або відновлює попередній update state там, де підтримується | AUTO/WIRE |
| Copy to collection | Копіює вибрані книги до іншої collection | AUTO |
| Reset navigation | Скидає collection navigation state | WIRE |
| Maintenance analyze | Аналізує collection без мутації | AUTO, WIRE |
| Maintenance dry-run | Показує план без застосування | AUTO, WIRE |
| Maintenance apply | Застосовує безпечні maintenance actions | AUTO, WIRE |

<!-- function-ref:import -->
## 5. Імпорт

| Функція | Що робить | Перевірка |
|---|---|---|
| Import book/archive | Імпорт одного файла або archive container | AUTO, E2E, WIRE |
| Import directory/catalog | Рекурсивно імпортує підтримувані файли каталогу | AUTO, WIRE |
| Import INPX | Імпортує INPX з `.inp`, `structure.info`, `archives.info` | AUTO, probe, WIRE |
| Add book | Додає книгу через головний UI flow | AUTO/WIRE |
| File chooser formats | FB2/FBD, EPUB, TXT, MOBI/PRC, AZW/AZW3, comics, audio та підтримувані archives | AUTO |
| Multi-book ZIP/JAR | Імпортує кілька book entries із archive | AUTO |
| 7z/RAR | Працює як archive container через безпечні adapters | AUTO |
| CBZ/CBR | Імпортує comic document | AUTO |
| MP3/M4B | Імпортує audiobook | AUTO |
| Cancel import | Кооперативно скасовує активний import | AUTO, WIRE |
| Import progress | Показує found/imported/error/progress state | AUTO/WIRE |

Nested archives навмисно не розпаковуються рекурсивно за замовчуванням.

<!-- function-ref:online-download -->
## 6. Online collections та завантаження книг

| Функція | Що робить | Перевірка |
|---|---|---|
| ConnectionScript | Виконує GET/POST/ADD/CHECK/REDIR/PAUSE сценарій | AUTO |
| Legacy URL preamble | Підтримує старий bare HTTP(S) preamble | AUTO |
| Download book | Завантажує remote artifact у persistent queue | AUTO, WIRE |
| Resume `.part` | Продовжує валідований частковий download | AUTO |
| Cancel download | Скасовує активне завантаження | AUTO, WIRE |
| Remove local copy | Видаляє локальну копію без видалення catalog record | AUTO/WIRE |
| Open remote book | Перед Reader/open показує canonical download confirmation | AUTO |
| Payload validation | Перевіряє semantic/archive safety перед atomic replace | AUTO |
| Force refresh | Не втрачає попередній валідний файл при невдалому replacement | AUTO |
| ZIP member resolution | Exact member → basename/LibID → single-FB2 fallback; ambiguous archive відхиляється | AUTO |
| Proxy/TLS/timeouts | Користувацькі network settings з encrypted secrets | AUTO/WIRE |
| Parallel download limits | Глобальний та per-host bounded concurrency | AUTO/WIRE |

<!-- function-ref:book-metadata -->
## 7. Книга, деталі та метадані

| Функція | Що робить | Перевірка |
|---|---|---|
| Open in Reader | Відкриває canonical selected book у внутрішній Читалці | AUTO, E2E, WIRE |
| Open external | Запускає налаштований external reader | AUTO, WIRE/LIVE |
| Close Reader | Зберігає/flush position і закриває Reader | AUTO/WIRE |
| Book details | Показує title/authors/series/genres/language/year/ISBN/publisher/status/progress/review/resources | AUTO/WIRE |
| Open artifact | Відкриває вибране фізичне представлення книги | AUTO/WIRE |
| Preferred artifact | Позначає preferred representation серед кількох artifacts | AUTO/WIRE |
| Open folder | Відкриває папку фізичного файла | WIRE/LIVE |
| Show images/resources | Показує ресурси/зображення книги | AUTO/WIRE |
| Similar books | Показує локально обчислені схожі книги за metadata з пояснюваними причинами; без cloud/AI profiling | AUTO/WIRE |
| Duplicate comic pages | Для CBZ/CBR/CB7 знаходить точні дублікати сторінок за SHA-256 та показує report-only результат без auto-delete | AUTO/WIRE |
| Edit metadata | Редагує metadata однієї книги | AUTO/WIRE |
| Batch metadata | Пакетно змінює metadata з validation/undo guards | AUTO |
| Metadata merge | Переглядає й застосовує candidate metadata з providers | AUTO |
| Custom-field values | Редагує typed custom fields книги | AUTO/WIRE |
| Manage custom fields | Створює/змінює definitions typed fields | AUTO/WIRE |
| Delete record | Видаляє catalog record окремо від remove-local | AUTO/WIRE |
| Undo last library operation | Скасовує останню підтримувану library mutation | AUTO/WIRE |

<!-- function-ref:batch -->
## 8. Пакетний вибір книг

| Функція | Що робить | Перевірка |
|---|---|---|
| Checkbox selection | Канонічний multi-book selection незалежний від current row | AUTO |
| Select all / deselect all | Пакетно змінює вибір | AUTO/WIRE |
| Rate | Пакетно задає рейтинг | AUTO/WIRE |
| Progress | Пакетно змінює прогрес | AUTO/WIRE |
| Mark read | Позначає вибрані книги прочитаними | AUTO/WIRE |
| Add to group | Додає вибрані книги до групи | AUTO/WIRE |
| Batch metadata | Запускає пакетне редагування для selection | AUTO |
| Batch export | Експортує selection | AUTO |
| Batch download | Завантажує selection | AUTO |
| Clear selection | Скидає checkbox selection | AUTO/WIRE |

<!-- function-ref:conversion -->
## 9. Конвертація книг

| Функція | Що робить | Перевірка |
|---|---|---|
| Convert current book | При порожньому checkbox selection конвертує current row | AUTO |
| Batch conversion | Конвертує всі checkbox-selected books | AUTO; WIN LIVE required |
| Common target formats | Показує intersection форматів, доступних для всіх selected books | AUTO |
| Bounded concurrency | Не більше 2 external conversions одночасно | AUTO; WIN LIVE required |
| Cancel batch | Активні jobs скасовуються, queued work не стартує | AUTO; WIN LIVE required |
| Partial failure | Успішні outputs залишаються опублікованими, summary відображає success/failure | AUTO; WIN LIVE required |
| Built-in/toolchain converters | Вибирає source-aware conversion route | AUTO |
| Optional Calibre | Використовує `ebook-convert` коли доступний | AUTO; WIN required |
| Calibre timeout policy | Default/effective legacy timeout 1800 s; custom 10…3600 s | AUTO; WIN required |
| Process cleanup | При cancel/timeout завершує process tree та cleanup staging | AUTO; WIN file-lock acceptance required |

<!-- function-ref:export-device -->
## 10. Export / Send to Device

| Функція | Що робить | Перевірка |
|---|---|---|
| Export selected books | Експортує вибрані книги до folder/device destination | AUTO |
| Export profiles | Створює/оновлює/видаляє reusable export profiles | AUTO/WIRE |
| Destination chooser | Обирає цільову папку | WIRE |
| Filename/subfolder templates | Формує portable filenames/paths | AUTO |
| Collision policy | Rename/skip/other supported policy без небезпечного overwrite | AUTO |
| Extract only | Експортує без conversion де вибрано | AUTO/WIRE |
| Target format | Обирає допустимий output format | AUTO |
| Device profile | Generic/Kindle/Kobo/PocketBook/Android/custom device preferences | AUTO |
| EJECT_SAFE completion | Виконує required file flush перед повідомленням про завершення | AUTO; physical eject is LIVE |
| Post-export action | Виконує налаштовану post action | AUTO/WIRE |
| Export history | Показує історію export | AUTO |
| Export list HTML/TXT/RTF | Експортує поточний список книг | AUTO/WIRE |
| Export INPX | Формує INPX export | AUTO/WIRE |

<!-- function-ref:reader -->
## 11. Reader — спільні функції

| Функція | Що робить | Перевірка |
|---|---|---|
| Supported reading | FB2/FBD, FB2-in-ZIP, EPUB, TXT, PDF, CBZ/CBR, supported DRM-free MOBI/PRC/AZW/AZW3, MP3/M4B | AUTO, E2E |
| Previous/next page | Сторінкова навігація | AUTO/WIRE |
| Previous/next chapter | Навігація розділами | AUTO/WIRE |
| Back | Повертається з Reader із position flush | AUTO/WIRE |
| One/two-page | Перемикає page mode | AUTO |
| Auto-scroll | Вмикає/вимикає автопрокрутку; reduced-motion може заборонити | AUTO |
| Zoom in/out/reset | Масштабує reader representation | AUTO/WIRE |
| Theme cycle | Перемикає Reader themes незалежно від app theme | AUTO |
| Reader settings | Presets, typography, colors, layout, navigation, status; показує джерело ефективного профілю | AUTO/WIRE |
| Reader settings — global | Застосовує поточні налаштування глобально | AUTO |
| Reader settings — format | Зберігає override для canonical формату поточної книги | AUTO |
| Reader settings — series | Зберігає override для нормалізованої серії поточної книги | AUTO |
| Reader settings — book | Зберігає override тільки для поточної книги | AUTO |
| Reader settings — clear override | Видаляє лише активний scoped override і відкриває нижчий рівень `Book > Series > Format > Global` | AUTO |
| Full screen | Перемикає fullscreen stage | WIRE |
| Reader sidebars | Показує/ховає application sidebars | AUTO |
| Bookmark | Створює bookmark у поточній позиції | AUTO |
| Bookmark list/jump/delete | Переглядає, переходить і видаляє bookmarks | AUTO |
| TOC | Показує вкладений зміст і переходить до section | AUTO |
| In-book search | Шукає текст і переходить до результату | AUTO |
| Book map | Навігаційна карта книги | AUTO |
| Position persistence | Autosave/retry/final flush та reopen | AUTO, E2E |
| Text selection | Drag, word selection, keyboard selection | AUTO |
| Copy | Копіює selected text | AUTO |
| Highlight / note | Створює durable annotation | AUTO, E2E |
| Dictionary | Явний offline/provider lookup selected text | AUTO |
| Translate | Явний provider-based translation з privacy confirmation для remote provider | AUTO/LIVE provider |
| Read Aloud | TTS start/pause/resume/stop, voice/rate | AUTO; system backend LIVE |

<!-- function-ref:pdf-reader -->
## 12. PDF Reader

- Previous/next page, zoom/reset, Fit Width, Fit Page, continuous scroll.
- Thumbnail sidebar та PDF outline/TOC.
- Text-layer search з page/snippet результатами; image-only PDF не отримує неявний OCR.
- Page bookmarks і persistence через спільний Reader position model.
- Lazy rasterization із bounded cache та stale-session guards.

**Перевірка:** `PdfDocumentSessionTest`, `PdfReaderSourceContractTest`, `PdfReaderWorkflowContractTest`, `PdfAnnotationSelectionDataTest` — AUTO.

<!-- function-ref:comic-reader -->
## 13. Comic Reader (CBZ/CBR)

- Fit Page, Fit Width, continuous scroll.
- Dual-page spreads.
- Manga RTL layout.
- Thumbnail navigation.
- Natural page ordering та lazy bounded decoding/cache.

**Перевірка:** `ComicDocumentSessionTest`, `ComicReaderSourceContractTest`, `ComicReaderWorkflowContractTest`, importer tests — AUTO.

<!-- function-ref:audio-reader -->
## 14. Audiobook Reader

- Play/pause та seek.
- Track/chapter navigation.
- Playback speed 0.5×–2.0×.
- Sleep timer.
- Exact-position bookmark та bookmark list.
- Resume progress.
- M4B embedded chapters через optional FFmpeg toolchain; synthetic chapter fallback.

**Перевірка:** audio session/grouping/progress tests — AUTO; real FFmpeg backend — LIVE/tool availability.

<!-- function-ref:annotations -->
## 15. Анотації та knowledge export

| Функція | Що робить | Перевірка |
|---|---|---|
| Annotation Manager | Глобальний workspace highlights/notes | AUTO, E2E, WIRE |
| Search/filter | Book/type/color/tag/date + text search | AUTO |
| Pagination | Bounded page navigation | AUTO/WIRE |
| Open in Reader | Переходить до anchor | AUTO |
| Edit | Note/color/tags | AUTO |
| Delete + undo | Confirmed deletion з one-step undo | AUTO |
| Batch tags/color | Пакетне додавання/зняття tags та color | AUTO/WIRE |
| CSV export | UTF-8 CSV selected/filtered scope | AUTO |
| Rich export | Markdown/JSON/self-contained HTML | AUTO |
| Readwise export | Експортує all/selected/filtered-book annotations через bounded remote batches; unchanged fingerprint пропускається | AUTO/WIRE; LIVE для real account |
| Digest | Створює читабельний конспект | AUTO |
| Knowledge export | Obsidian/Joplin-compatible one-file-per-book Markdown | AUTO |
| Re-export policy | REPLACE_MANAGED / SKIP_EXISTING / FAIL_IF_EXISTS | AUTO |

<!-- function-ref:authors-updates -->
## 16. Автори, стеження та updates

| Функція | Що робить | Перевірка |
|---|---|---|
| Author workspace | Показує книги автора, статистику, series grouping | AUTO |
| Sort | Title / year / rating / series | AUTO/WIRE |
| Collapse/expand series | Згортає/розгортає series groups без втрати selection | AUTO |
| Select author/series books | Master/series checkbox з partial state | AUTO |
| Edit author description | Редагує опис автора | AUTO/WIRE |
| Follow/unfollow | Вмикає/вимикає стеження за автором | AUTO/WIRE |
| Followed Authors | Огляд авторів із непрочитаними updates | AUTO/WIRE |
| Acknowledge author | Позначає updates автора переглянутими | AUTO/WIRE |
| Updates workspace | New/updated books grouped by author | AUTO/WIRE |
| Download selected update | Завантажує обраний update | AUTO/WIRE |
| Acknowledge selected/all | Позначає update(s) опрацьованими | AUTO/WIRE |

<!-- function-ref:groups -->
## 17. Групи

- Create, rename, delete, clear group.
- Add/remove book from group.
- Перегляд group details і paginated group books.
- Batch add selected books to group.

**Перевірка:** group repository/lifecycle tests + FXML wiring — AUTO/WIRE.

<!-- function-ref:reading-lists -->
## 18. Списки читання

| Функція | Що робить | Перевірка |
|---|---|---|
| Create / Rename / Delete | Керує окремими user-owned списками без мутації Groups/Smart Collections | AUTO, WIRE |
| Add / Remove | Додає одну або кілька книг; повторне додавання не створює дубль | AUTO, WIRE |
| Manual order | Зберігає contiguous deterministic `position=0..N-1`; підтримує ↑/↓, Alt+↑/Alt+↓ і drag-and-drop | AUTO, WIRE |
| Почати/продовжити список | Відкриває першу незавершену книгу за ручним порядком | AUTO, WIRE |
| Context / checkbox selection | «Додати до списку читання» підтримує одну книгу або поточну checkbox-selection | AUTO, WIRE |
| Portable transfer | Export/restore зберігає membership і порядок через stable book identity | AUTO |
| Delete safety | Видалення списку не видаляє book records/files; delete book не залишає dangling item | AUTO |

На Dashboard секція **«Далі у серіях»** є derived query: серія має завершену книгу, не має активної незавершеної книги, а candidate — мінімальний unread valid sequence після останньої завершеної. `null`/ambiguous sequence не вгадується; Continue Reading та On Deck не дублюють одну книгу.

<!-- function-ref:health-stats -->
## 19. Library Health, integrity та statistics

| Функція | Що робить | Перевірка |
|---|---|---|
| Library Health scan | Перевіряє local artifacts, metadata gaps, duplicates, SQLite/Lucene, backup age | AUTO |
| Missing/corrupt/changed drill-down | Окремо показує різні класи artifact problems | AUTO/WIRE |
| Duplicate drill-down | Переходить до duplicate evidence | AUTO/WIRE |
| Metadata/index/content-index drill-down | Показує стан відповідної категорії | AUTO/WIRE |
| Export health report | Експортує текстовий report | AUTO/WIRE |
| Rebuild content index | Запускає background rebuild | AUTO/WIRE |
| Cancel content rebuild | Скасовує rebuild | AUTO/WIRE |
| Safe repair guidance | Не виконує blind destructive repair | AUTO/WIRE |
| Statistics | Books/authors/series/genres/local/remote/read/unread/etc. | AUTO/WIRE |
| Rebuild search index | Перебудовує Lucene index | AUTO/WIRE |
| Optimize database | Запускає безпечний SQLite optimization/VACUUM flow | AUTO/WIRE |

<!-- function-ref:duplicates -->
## 20. Duplicate review

- Перегляд fuzzy duplicate candidates.
- Refresh candidate list.
- Review/merge selected duplicate candidate.
- Undo latest merge.
- Exact duplicate scan із progress/counters.
- Cancel exact scan.

**Перевірка:** duplicate presenter/scanner/candidate-store tests — AUTO; UI surface — WIRE.

<!-- function-ref:organizer -->
## 21. Library Organizer

- Source: current collection або external folder.
- Destination chooser та directory/filename templates.
- Organization/conversion/optimization rules і profiles.
- Analysis із progress/cancel.
- Preview table з action/source impact/reason/profile/target format/savings/warnings/path/duplicate/confidence.
- Filter preview, select/clear visible rows.
- Apply only selected allowed actions із confirmation.
- Cancel active apply.
- Undo completed organization.
- Crash/recovery review та rollback.
- Open destination та export report.

**Перевірка:** analyze/plan/apply/undo/path/archive/crash-manifest tests — AUTO; wizard action wiring — WIRE.

<!-- function-ref:operations -->
## 22. Operation Center

- Перегляд running/completed operations поточного session.
- Тип, stage, progress, timestamps, duration, result/error details.
- Refresh.
- Cancel selected operation, якщо operation підтримує cancellation.
- Clear completed history без видалення outputs.
- Status bar переходить до Operation Center.

**Перевірка:** operation coordinator/history/UI text/service tests + WIRE.

<!-- function-ref:backup-restore -->
## 23. Backup, restore та portable user data

| Функція | Що робить | Перевірка |
|---|---|---|
| Backup | Створює versioned backup із staged publication/manifest/SHA-256 | AUTO, E2E |
| Backup destination | Вибір цільової папки та overwrite confirmation | AUTO/WIRE |
| Restore | Відновлює database/metadata відповідно до options | AUTO, E2E |
| Tamper validation | Відхиляє inconsistent/tampered generation | AUTO |
| Import user data | Імпортує portable `user-data.json` за stable LibID | AUTO |
| Export user data | Експортує ratings/progress/reviews/bookmarks/groups/search/settings/custom fields, Reading Lists та scoped Reader profiles; current schema v6 | AUTO |
| Legacy backup upgrade | Старі database-only backups проходять normal Flyway path | AUTO |

<!-- function-ref:sync -->
## 24. Folder/WebDAV sync

- Synchronize folder із async availability/operation coordination.
- Local-folder sync adapter.
- WebDAV endpoint/username/password/sync-key configuration.
- Save, test connection, run sync, copy sync key, clear configuration.
- Encrypted sync payload envelope.
- Conflict review dialog і explicit conflict selection.
- Cross-device progress projection, включно з audiobook progress.

**Перевірка:** folder-sync/WebDAV/conflict/sync model tests — AUTO; реальний remote WebDAV endpoint — LIVE.

<!-- function-ref:settings -->
## 25. Settings

### General
- UI language.
- Confirm delete.
- Restore session.
- Reduced Motion.
- Data/portable path information.
- Language diagnostics.
- Support bundle preview/export із selectable logs/thread/release-doc content.

### Theme
- SYSTEM/LIGHT/DARK/AMOLED/CUSTOM.
- Background/panel/text/accent/series/book/downloaded colors.
- UI font size.
- Reset theme values.

### Indexing
- Eco / Balanced / Fast resource profiles.
- Worker/I/O policy та optional pause-on-battery.

### External reading
- External command templates per format.
- Placeholder expansion та command test.

### Converters
- Toolchain mode/profile/reading-system/acceptance mode.
- Calibre executable і timeout.
- fb2cng/Pandoc/libmobi/EPUBCheck diagnostics/probes.
- Browse executable paths та validation.

### Device export
- Default filename/subfolder templates та related export defaults.

### Online
- Connect/read timeouts.
- Global/per-host download concurrency.
- Proxy mode/host/port/user/encrypted password.
- Custom TLS trust store/type/encrypted password.

**Перевірка:** settings/theme/indexing/security/diagnostic tests + WIRE; external commands/tools — LIVE where platform-dependent.

<!-- function-ref:book-actions -->
## 26. Book Actions та shortcuts

- Create/update/delete external book-action profiles.
- Command executable, argv, working directory, wait behavior та placeholders.
- Preview command expansion.
- Run action from book context menu.
- Central command registry with user-visible/hidden actions.
- Customize keyboard shortcuts.
- Detect invalid shortcut syntax and conflicts.
- Reset command preferences to defaults.

**Перевірка:** action profile/execution/materialization tests + CoreActions registry audit — AUTO/WIRE.

<!-- function-ref:opds -->
## 27. OPDS

- Configure bind address і port.
- Optional Basic Auth + encrypted password.
- Autostart.
- Loopback HTTP by default; LAN exposure requires TLS.
- Generate/regenerate managed self-signed certificate.
- Import X.509 PEM certificate + unencrypted PKCS#8 key.
- Show SHA-256 certificate fingerprint та trust guidance.
- Create Bearer token with device name and CATALOG_READ / DOWNLOAD scopes.
- One-time token secret display; hash-only persistence.
- List/refresh/revoke tokens.
- Start/stop server і refresh status.
- OPDS 1.x + OPDS 2.0 catalogue/search/collections/groups/favorites/continue-reading endpoints.
- Stream local book download.
- `/health` lifecycle probe.

**Перевірка:** OPDS settings/token/server/TLS/catalog E2E tests — AUTO/E2E; real LAN/client interoperability — LIVE.

<!-- function-ref:mcp -->
## 28. MCP

Окремий read-only MCP runtime надає підтримувані сценарії читання library data зовнішнім MCP-клієнтам. Він не надає desktop-користувачу destructive mutation API.

**Перевірка:** `MyHomeLibMcpServerProtocolTest` — AUTO; конкретний зовнішній MCP host/client — LIVE.

### Secure application updates
- Канали STABLE/BETA/MANUAL, manual check, release notes, download/verify staging і Restart and update.
- Manifest перевіряється за Ed25519 trust key; artifact — за declared length і SHA-256 до publication plan.
- Оновлення блокується під час destructive/active library operation; updater працює окремим helper process і rollback-ить replacement без health marker.
- Manifest URLs, channel, auto-check/install policy, installation/helper paths — application settings; private signing key не входить у продукт.
- Windows installer/upgrade і real packaged restart/rollback залишаються LIVE acceptance.

<!-- function-ref:plugins-ai -->
## 29. Plugins та Integration Center

### Plugins
- Discover plugin manifests/services.
- Показати compatibility/services/permissions/core overrides.
- Enable/disable відповідно до trust/permission state.
- Approval прив'язаний до exact plugin id/version.
- Quarantine після managed invocation failure.

### Readwise
- Token зберігається лише через `SecretStore` і не відображається назад у UI.
- Test connection, export all з Integration Center та selected/filtered-book export з Annotation Manager.
- Retry дозволений лише для idempotent-safe batch identity; локальний checkpoint містить тільки stable annotation id + fingerprint.
- Видалення локальної annotation каскадно прибирає локальний checkpoint; V1 не виконує remote delete у Readwise.

### AI providers
- Provider-neutral optional AI extension point.
- Configure provider через Integration Center.
- Summarize selected book.
- Ask a question about selected book.
- Built-in/OpenAI-compatible provider contracts залишаються optional; відсутність provider не повинна ламати core library.

**Перевірка:** plugin loader/manager/harness/sample tests та AI provider/desktop integration tests — AUTO; реальні зовнішні AI endpoint/credentials — LIVE.

<!-- function-ref:kobo-sync -->
### Kobo Sync
- Enable Kobo native sync і перегляд device/SecretStore status у Integration Center.
- Regenerate credential; secret показується одноразово й зберігається тільки через `SecretStore`.
- Copy одноразового credential у clipboard.
- Підготувати incremental sync projection/cursor вручну.
- Revoke device/credential.
- Stable book identity = MyHomeLib book ID + artifact/content fingerprint; title-based matching не використовується.
- EPUB/KEPUB віддається напряму; інші convertible формати використовують чинний converter toolchain lazy-on-download.
- Reading progress синхронізується в обидва боки з version/cursor conflict authority; stale update відхиляється.

**Перевірка:** application/protocol/persistence deterministic tests — AUTO; first sync, library appearance, open/read, cover/metadata, reconnect і revoke на фізичному Kobo — LIVE.

<!-- function-ref:localization-help -->
## 30. Localization, accessibility та help

- Bundled Ukrainian, English, Bulgarian UI catalogs.
- Compatible external language files.
- Stable localized format placeholders.
- F1 context help через `HelpTopicRegistry`.
- Help → «Усі функції користувача» відкриває вбудований повний каталог end-user можливостей та меж їх автоматичної/LIVE перевірки.
- Accessible text/name для icon controls та keyboard-focusable actions.
- Reduced Motion.
- Theme contrast/accessibility contracts.

Під час V24 functional audit виправлено F1 routing для `downloaded-author`, `publisher`, `followed-authors`, `operations` та `annotations`; додано окремі `annotations` і `operations` help topics у UK/EN/BG.

<!-- function-ref:non-desktop -->
## 31. Технічно доступні, але не desktop-кнопки

Наступні можливості є частиною продукту, але зазвичай використовуються опосередковано через UI flow, sidecar або зовнішній клієнт: Web Library/browser reading, OPDS HTTP endpoints, MCP protocol, plugin API runtime providers, remote metadata/translation/AI providers. Їх не слід змішувати з видимими FXML-командами головного desktop UI.

## Межі цього каталогу

Каталог охоплює end-user product capabilities у поточному V34 source state. Developer-only build/release scripts, benchmark harnesses, migration utilities та Plugin SDK authoring helpers не рахуються як звичайні функції кінцевого користувача.

Повна live-перевірка **Windows Calibre conversion/process cleanup**, реальних external readers/device eject, системного TTS/FFmpeg та зовнішніх network providers не може бути чесно замінена Linux static/automated audit; ці сценарії мають окремий статус LIVE/WIN у functional audit.


## Матриця форматів і користувацьких операцій

Позначення: **✓** — є product contract/automated support; **умовно** — залежить від adapter/external tool або metadata container; **—** — операція не заявлена як загальний контракт для формату. Ця таблиця описує MyHomeLib, а не теоретичні можливості сторонніх бібліотек.

| Формат / контейнер | Import | Internal read | Organizer metadata | Convert | Organize | Export | Validate |
|---|---:|---:|---:|---:|---:|---:|---:|
| FB2 / FBD | ✓ | ✓ | ✓ structured | ✓/engine-dependent | ✓ | ✓ | ✓ |
| FB2.ZIP | ✓ | ✓ | ✓ structured | ✓/engine-dependent | ✓ | ✓ | ✓ archive bounds |
| EPUB | ✓ | ✓ | ✓ structured | ✓ | ✓ | ✓ | ✓ |
| TXT | ✓ | ✓ | filename-only | ✓/engine-dependent | ✓ | ✓ | ✓ basic |
| MOBI / PRC | ✓ | ✓ | ✓ structured | ✓/external engine | ✓ | ✓ | ✓ |
| AZW / AZW3 / AZW4 | ✓ | ✓ для підтриманих DRM-free variants | ✓ structured | ✓/external engine | ✓ | ✓ | ✓ |
| PDF | ✓ | ✓ PDF Reader | ✓ document info | engine-dependent | ✓ | ✓ | ✓ structural/runtime |
| DOCX | ✓ | external/internal flow залежно від action | ✓ structured | engine-dependent | ✓ | ✓ | archive/XML bounds |
| ODT | ✓ | external/internal flow залежно від action | ✓ structured | engine-dependent | ✓ | ✓ | archive/XML bounds |
| RTF / HTML / HTMLZ / Markdown | ✓ | залежно від конкретного reader/action | filename-only | engine-dependent | ✓ | ✓ | basic/archive для HTMLZ |
| CBZ / CBC | ✓ | ✓ Comic Reader | ✓ ComicInfo/XML коли наявний | — | ✓ | ✓ | ✓ archive bounds |
| CBR / CB7 | ✓ | ✓ через comic/archive adapter | filename-only | — | ✓ | ✓ | adapter-dependent |
| CHM / DJVU / LIT / LRF / PDB / PML/PMLZ / RB / SNB / TCR / TXTZ | recognized/import paths | залежить від registered reader/external action | filename-only | engine-dependent | ✓ non-inventing metadata fallback | ✓ | format/adapter-dependent |
| Generic multi-book ZIP | ✓ як container | окремі supported entries | N/A для container; entries аналізуються | — | ✓ extract entries, container retained | ✓ за flow | ✓ archive bounds |
| MP3 / M4B | ✓ | ✓ Audio Reader | — | ffmpeg-dependent для transcoding paths | — | ✓ | runtime-dependent |

Organizer окремо machine-checkable гарантує, що кожне recognized book extension має `STRUCTURED`, `FILENAME_ONLY` або `NOT_APPLICABLE` classification; `UNCLASSIFIED` для recognized extension є test failure.

## Матриця optional / external dependencies

| Dependency / середовище | Для чого | Стан без dependency | Evidence level |
|---|---|---|---|
| Calibre `ebook-convert` | частина conversion paths | функція недоступна/engine unavailable, без silent success | AUTO contract + **WIN live required** |
| libmobi `mobitool` | MOBI-family conversion/tooling | graceful unavailable path | AUTO + **WIN live required** |
| Pandoc/FBC та інші configured converters | додаткові source/target combinations | тільки advertised available capabilities | AUTO/external |
| FFmpeg | audio/transcoding functions | dependent action unavailable/error contract | AUTO + LIVE |
| OS TTS voices | TTS | залежить від installed voices/runtime | AUTO + LIVE |
| External reader executable | Open external | потребує configured executable | AUTO + LIVE |
| Device/mount + OS safe eject | Send to device/eject | file export contract працює окремо; physical eject не емулюється | AUTO + LIVE/WIN |
| WebDAV server | sync | локальні conflict/crypto/contracts автоматизовані; live endpoint optional | AUTO + LIVE |
| Metadata/translation/AI providers | integration features | inactive/unavailable until configured/consented | AUTO + LIVE |
| External OPDS/MCP clients | protocol interoperability | server contracts/e2e є; client-specific live acceptance окрема | AUTO + LIVE |
| Windows installer/portable runtime | platform release | не може бути підтверджений Linux/Xvfb | **WIN** |

## Зв'язок із UI coverage

Кожна актуальна FXML/programmatic JavaFX action і кожна явно керована Web Library offline action має entry у `docs/current/UI-ACTION-COVERAGE.json` та явний `user_function_ref` на один із flow цього документа. V34 inventory містить **484/484** дії: **273 FXML + 205 programmatic + 6 Web**, `unmapped=0`. Окремий strict behavior registry дає **483 automated action-specific + 1 justified exclusion = 484/484**. Generic controller/delegate test не рахується evidence без action/control/behavior ownership.

<!-- function-ref:web-offline -->
## 30. Web Library PWA / офлайн-читання

**Поверхня:** браузерна Web Library `/web/` у чинному OPDS sidecar; explicit offline actions входять у спільний machine-checkable UI action inventory як `WEB`.

- Встановлення Web Library як PWA через browser install UX.
- **Зберегти для читання офлайн** — явне кешування однієї підтримуваної Web Reader книги.
- **Видалити офлайн-копію** — очищення кешу конкретної книги.
- **Очистити всі офлайн-копії** — видалення всіх explicit offline-book caches без видалення app shell.
- Офлайн app shell/fallback без автоматичного кешування бібліотеки; збереження книг bounded до 20 completed book caches.
- Черга прогресу в IndexedDB та автоматичний ідемпотентний replay після reconnect.
- Stale queued progress не перезаписує новіший канонічний прогрес на сервері.

**Перевірка:** Web PWA/Service Worker/server contract tests + OPDS/Web E2E; real installability/offline behavior на підтримуваному browser — LIVE/Browser acceptance, якщо немає детерміністичного Chromium toolchain у checkpoint.
