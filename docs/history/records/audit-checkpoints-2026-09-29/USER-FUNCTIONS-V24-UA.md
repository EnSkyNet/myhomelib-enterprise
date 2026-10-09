# MyHomeLib 8.0.0 — повний каталог користувацьких функцій

Дата аудиту: 2026-09-29  
Continuation source: V23 clean checkpoint.  
Призначення: канонічний опис функцій, доступних кінцевому користувачу desktop MyHomeLib.

## Як читати статус перевірки

- **E2E** — є end-to-end journey test через кілька шарів продукту.
- **AUTO** — є цільові автоматизовані behavioral/contract/integration тести.
- **WIRE** — перевірено існування UI entry point, controller handler/command wiring та відсутність розірваного FXML binding; це не прирівнюється до live UI execution.
- **LIVE** — потрібен реальний зовнішній сервіс/пристрій/ОС для повної приймальної перевірки.
- **WIN** — обов'язкова Windows x64 acceptance.

Машинний повний реєстр усіх FXML entry points і програмно створених action surfaces: `docs/release/FUNCTIONAL-COVERAGE-V24.json`.

## 1. Головне вікно, навігація та представлення

| Функція | Що робить | Основна точка входу | Перевірка |
|---|---|---|---|
| Головна / Dashboard | Показує загальний стан бібліотеки, останні/нові книги та продовження читання | Library → Home | AUTO, WIRE |
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
| Edit metadata | Редагує metadata однієї книги | AUTO/WIRE |
| Batch metadata | Пакетно змінює metadata з validation/undo guards | AUTO |
| Metadata merge | Переглядає й застосовує candidate metadata з providers | AUTO |
| Custom-field values | Редагує typed custom fields книги | AUTO/WIRE |
| Manage custom fields | Створює/змінює definitions typed fields | AUTO/WIRE |
| Delete record | Видаляє catalog record окремо від remove-local | AUTO/WIRE |
| Undo last library operation | Скасовує останню підтримувану library mutation | AUTO/WIRE |

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
| Reader settings | Presets, typography, colors, layout, navigation, status | AUTO/WIRE |
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

## 12. PDF Reader

- Previous/next page, zoom/reset, Fit Width, Fit Page, continuous scroll.
- Thumbnail sidebar та PDF outline/TOC.
- Text-layer search з page/snippet результатами; image-only PDF не отримує неявний OCR.
- Page bookmarks і persistence через спільний Reader position model.
- Lazy rasterization із bounded cache та stale-session guards.

**Перевірка:** `PdfDocumentSessionTest`, `PdfReaderSourceContractTest`, `PdfReaderWorkflowContractTest`, `PdfAnnotationSelectionDataTest` — AUTO.

## 13. Comic Reader (CBZ/CBR)

- Fit Page, Fit Width, continuous scroll.
- Dual-page spreads.
- Manga RTL layout.
- Thumbnail navigation.
- Natural page ordering та lazy bounded decoding/cache.

**Перевірка:** `ComicDocumentSessionTest`, `ComicReaderSourceContractTest`, `ComicReaderWorkflowContractTest`, importer tests — AUTO.

## 14. Audiobook Reader

- Play/pause та seek.
- Track/chapter navigation.
- Playback speed 0.5×–2.0×.
- Sleep timer.
- Exact-position bookmark та bookmark list.
- Resume progress.
- M4B embedded chapters через optional FFmpeg toolchain; synthetic chapter fallback.

**Перевірка:** audio session/grouping/progress tests — AUTO; real FFmpeg backend — LIVE/tool availability.

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
| Digest | Створює читабельний конспект | AUTO |
| Knowledge export | Obsidian/Joplin-compatible one-file-per-book Markdown | AUTO |
| Re-export policy | REPLACE_MANAGED / SKIP_EXISTING / FAIL_IF_EXISTS | AUTO |

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

## 17. Групи

- Create, rename, delete, clear group.
- Add/remove book from group.
- Перегляд group details і paginated group books.
- Batch add selected books to group.

**Перевірка:** group repository/lifecycle tests + FXML wiring — AUTO/WIRE.

## 18. Library Health, integrity та statistics

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

## 19. Duplicate review

- Перегляд fuzzy duplicate candidates.
- Refresh candidate list.
- Review/merge selected duplicate candidate.
- Undo latest merge.
- Exact duplicate scan із progress/counters.
- Cancel exact scan.

**Перевірка:** duplicate presenter/scanner/candidate-store tests — AUTO; UI surface — WIRE.

## 20. Library Organizer

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

## 21. Operation Center

- Перегляд running/completed operations поточного session.
- Тип, stage, progress, timestamps, duration, result/error details.
- Refresh.
- Cancel selected operation, якщо operation підтримує cancellation.
- Clear completed history без видалення outputs.
- Status bar переходить до Operation Center.

**Перевірка:** operation coordinator/history/UI text/service tests + WIRE.

## 22. Backup, restore та portable user data

| Функція | Що робить | Перевірка |
|---|---|---|
| Backup | Створює versioned backup із staged publication/manifest/SHA-256 | AUTO, E2E |
| Backup destination | Вибір цільової папки та overwrite confirmation | AUTO/WIRE |
| Restore | Відновлює database/metadata відповідно до options | AUTO, E2E |
| Tamper validation | Відхиляє inconsistent/tampered generation | AUTO |
| Import user data | Імпортує portable `user-data.json` за stable LibID | AUTO |
| Export user data | Експортує ratings/progress/reviews/bookmarks/groups/search/settings/custom fields | AUTO |
| Legacy backup upgrade | Старі database-only backups проходять normal Flyway path | AUTO |

## 23. Folder/WebDAV sync

- Synchronize folder із async availability/operation coordination.
- Local-folder sync adapter.
- WebDAV endpoint/username/password/sync-key configuration.
- Save, test connection, run sync, copy sync key, clear configuration.
- Encrypted sync payload envelope.
- Conflict review dialog і explicit conflict selection.
- Cross-device progress projection, включно з audiobook progress.

**Перевірка:** folder-sync/WebDAV/conflict/sync model tests — AUTO; реальний remote WebDAV endpoint — LIVE.

## 24. Settings

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

## 25. Book Actions та shortcuts

- Create/update/delete external book-action profiles.
- Command executable, argv, working directory, wait behavior та placeholders.
- Preview command expansion.
- Run action from book context menu.
- Central command registry with user-visible/hidden actions.
- Customize keyboard shortcuts.
- Detect invalid shortcut syntax and conflicts.
- Reset command preferences to defaults.

**Перевірка:** action profile/execution/materialization tests + CoreActions registry audit — AUTO/WIRE.

## 26. OPDS

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

## 27. MCP

Окремий read-only MCP runtime надає підтримувані сценарії читання library data зовнішнім MCP-клієнтам. Він не надає desktop-користувачу destructive mutation API.

**Перевірка:** `MyHomeLibMcpServerProtocolTest` — AUTO; конкретний зовнішній MCP host/client — LIVE.

## 28. Plugins та Integration Center

### Plugins
- Discover plugin manifests/services.
- Показати compatibility/services/permissions/core overrides.
- Enable/disable відповідно до trust/permission state.
- Approval прив'язаний до exact plugin id/version.
- Quarantine після managed invocation failure.

### AI providers
- Provider-neutral optional AI extension point.
- Configure provider через Integration Center.
- Summarize selected book.
- Ask a question about selected book.
- Built-in/OpenAI-compatible provider contracts залишаються optional; відсутність provider не повинна ламати core library.

**Перевірка:** plugin loader/manager/harness/sample tests та AI provider/desktop integration tests — AUTO; реальні зовнішні AI endpoint/credentials — LIVE.

## 29. Localization, accessibility та help

- Bundled Ukrainian, English, Bulgarian UI catalogs.
- Compatible external language files.
- Stable localized format placeholders.
- F1 context help через `HelpTopicRegistry`.
- Accessible text/name для icon controls та keyboard-focusable actions.
- Reduced Motion.
- Theme contrast/accessibility contracts.

Під час V24 functional audit виправлено F1 routing для `downloaded-author`, `publisher`, `followed-authors`, `operations` та `annotations`; додано окремі `annotations` і `operations` help topics у UK/EN/BG.

## 30. Технічно доступні, але не desktop-кнопки

Наступні можливості є частиною продукту, але зазвичай використовуються опосередковано через UI flow, sidecar або зовнішній клієнт: Web Library/browser reading, OPDS HTTP endpoints, MCP protocol, plugin API runtime providers, remote metadata/translation/AI providers. Їх не слід змішувати з видимими FXML-командами головного desktop UI.

## Межі цього каталогу

Каталог охоплює end-user product capabilities у V23/V24 source state. Developer-only build/release scripts, benchmark harnesses, migration utilities та Plugin SDK authoring helpers не рахуються як звичайні функції кінцевого користувача.

Повна live-перевірка **Windows Calibre conversion/process cleanup**, реальних external readers/device eject, системного TTS/FFmpeg та зовнішніх network providers не може бути чесно замінена Linux static/automated audit; ці сценарії мають окремий статус LIVE/WIN у functional audit.
