# MyHomeLib Enterprise — Reader fixes (2026-08-23)

## Що було зламано

Головна причина порожнього вікна читалки була у JavaFX render pipeline:

1. `ReaderView` створював `Canvas` і передавав його в `JavaFxReaderRenderer`.
2. `ReaderCanvas` створював **інший** `Canvas` і саме його додавав у UI.
3. Renderer малював текст на першому, невидимому Canvas.
4. Додатково `ReaderCanvas` не передавав актуальні `PageDimensions` у `ReaderEngine` перед `renderPage()`, тому engine міг повертатися без рендеру.

Також layout починав довгий абзац із його початку, а не з поточного `textOffset`, що могло повторювати один абзац на наступних сторінках.

## Що виправлено

- Один спільний JavaFX `Canvas` для View + Renderer.
- `PageDimensions` передаються в engine перед кожним рендером.
- Посторінковий layout продовжує абзац із точного `textOffset`.
- Звичайні FB2 `<p>`, `subtitle`, `v`, `text-author` реєструються як параграфи.
- FB2 parser переведено на реальний StAX streaming: немає `readAllBytes()` для всього FB2.
- Додано fallback кодувань UTF-8 / Windows-1251 / CP866 / KOI8-R / ISO-8859-5.
- Додано вкладений TOC і коректні top-level chapter ranges.
- Вбудовані FB2 ресурси зберігаються через `HybridResourceRepository`: дрібний обмежений RAM-cache, великі payload — temp files.
- ZIP parser більше не тримає розпакований FB2 до 50 MB як `byte[]`; використовує bounded temp file.
- Renderer завантажує картинки ліниво та має LRU-cache до 8 JavaFX images.
- Page cache — малий LRU (5 сторінок), ключ враховує всі margins і viewport.
- Пошук іде параграфами, без створення повної lower-case копії книги.
- Кеш метрик не зберігає цілі рядки книги.
- Виправлено подвійне масштабування заголовків.
- Реалізовано налаштування теми, шрифту, розміру, інтервалів, відступів, автопрокрутки та toolbar з persistence.
- Позиція читання повідомляється після page/chapter/jump navigation.
- Back/Esc не закривають книгу до того, як workspace збереже позицію.
- Auto-scroll працює як енергоощадний автоматичний перехід сторінок.
- Resize events коалесуються, історія сторінок скидається після reflow.
- Додано unit tests для FB2 parser та continuation довгого параграфа.
- FB2 inline styles (`strong/emphasis/link/code/sup/sub`) проходять через layout як компактні `TextRunLayout` і реально рендеряться Canvas-ом.
- Вкладені `strong + emphasis` зводяться до `BOLD_ITALIC`.
- `justify` тепер справжній: міжслівний простір розподіляється на рівні поточної сторінки без DOM/WebView.
- Великі `<binary>` FB2 більше не створюють `StringBuilder(base64) + byte[]`; base64 спулиться/декодується потоково, decoded payload одразу переходить у bounded RAM/temp storage.
- Fallback `previousPage()` після TOC/search/percent jump шукає реальну попередню сторінку без глобальної page-map.
- Додано mobile-like tap/swipe navigation: лівий/правий tap, горизонтальний swipe, центральний tap ховає/показує toolbar.
- Touchpad/mouse wheel має накопичення delta + debounce, щоб одна прокрутка не перегортала багато сторінок.
- `Ctrl+F` тепер реально викликає пошук, як заявлено в toolbar tooltip.

## Основні змінені/додані файли

### myhomelib-reader

- `api/BookDocumentMetadataSnapshot.java` (new)
- `core/ReaderEngine.java`
- `core/cache/PageCache.java`
- `core/resource/HybridResourceRepository.java` (new)
- `format/fb2/Fb2StreamingParser.java`
- `format/zip/ZipParser.java`
- `layout/FontMetricsProviderImpl.java`
- `layout/TextLayoutEngine.java`
- `model/LineLayout.java`
- `model/TextRunLayout.java` (new)
- `model/PageLayout.java`
- `render/javafx/AutoScrollController.java`
- `render/javafx/FontProvider.java`
- `render/javafx/JavaFxReaderRenderer.java`
- `render/javafx/ReaderCanvas page-mode flow.java`
- `render/javafx/ReaderCanvas.java`
- `render/javafx/ReaderToolbar.java`
- `render/javafx/ReaderView.java`
- `service/ReaderSearchService.java`
- `src/test/.../Fb2StreamingParserTest.java` (new)
- `src/test/.../TextLayoutEngineTest.java` (new/expanded)
- `src/test/.../ReaderEngineNavigationTest.java` (new)

### myhomelib-ui

- `reader/NewReaderWorkspaceController.java`
- `reader/ReaderSettingsDialog.java` (new)
- `reader/ReaderSettingsMapper.java` (new)

### myhomelib-domain

- `model/reader/ReaderPreferences.java` — додано `pageMode`.

## Перевірка

У sandbox є Java/Javac 21, але немає Maven та локально встановлених Maven/OpenJFX/Lombok dependencies. Тому повний `mvn test` тут запустити неможливо без мережевого доступу до Maven Central.

Виконано:

- компіляція чистого Java subset (models + settings + hybrid resource repository) через `javac` — OK;
- smoke-test `HybridResourceRepository` (RAM + temp-file spill + cleanup) — OK;
- окремий smoke-test streaming `HybridResourceRepository.add(InputStream, maxBytes)` на 700 KB payload + rejection limit — OK;
- окремий parser harness: nested `BOLD_ITALIC` + 300 KB base64 binary через streaming repository — OK;
- окремий layout harness: mixed NORMAL/BOLD/ITALIC runs — OK;
- окремий navigation harness: jump offset 10000 -> previous page offset 9732, previous end 9990 (не мікро-відкат) — OK;
- статична перевірка змінених Java-файлів на синтаксичні помилки;
- перевірка відсутності TODO/FIXME у reader та reader UI;
- перевірка всіх змінених constructor usages (`LineLayout`, `AutoScrollController`, `ReaderSettings`).

На машині розробника виконати:

```bash
mvn -pl myhomelib-reader -am test
mvn clean verify
```

Для запуску desktop застосунку використовуйте існуючий bootstrap/IntelliJ configuration проєкту.

## Android / iOS

Поточний архів — це **виправлена desktop JavaFX версія**, а не готовий Android/iOS build. Reader logic уже значно ближче до portability: parser, text storage, pagination, search і model не залежать від WebView. Але JavaFX renderer/UI і Spring Boot desktop shell не можна просто зібрати як нативний Android/iOS застосунок.

Рекомендований наступний крок:

1. Винести `reader-api + parser + text/layout + position/search` у `myhomelib-reader-core` без JavaFX.
2. Залишити `myhomelib-reader-javafx` як desktop adapter.
3. Для Android додати Canvas/Compose renderer, storage adapter і lifecycle adapter.
4. Для iOS практичніше мати Kotlin Multiplatform/Compose Multiplatform UI/core boundary або окремий native renderer; Spring desktop infrastructure не переносити на mobile.
5. Library/catalog/domain use cases залишити за hexagonal ports, а SQLite/filesystem/preferences реалізувати окремими platform adapters.

## Що ще не є повною AlReader parity

Поточна версія закриває критичний reader і дає low-memory paged rendering, але наступна черга для повної AlReader-подібної функціональності:

- мовні словники переносу слів (зараз довгі слова переносяться без повноцінного словника hyphenation);
- footnote popup/overlay замість простого включення notes body;
- точний глобальний page count без повної page-map (зараз номер/кількість сторінок приблизні навмисно заради RAM);
- EPUB/MOBI/AZW підтримка, якщо вона потрібна;
- окремі Android/iOS renderer modules.
