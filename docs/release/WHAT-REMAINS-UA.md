# MyHomeLib 8.0.0 — що ще треба зробити перед публічним релізом

**Дата стану:** 18.09.2026  
**Статус локального релізного аудиту:** **PASS**. У доступному Linux/offline-середовищі відомих незакритих дефектів коду, локалізації або Spring wiring після фінального аудиту не залишено. Один повний фінальний Maven-прогін після всіх виправлень завершився `BUILD SUCCESS`. Нижче залишені тільки перевірки, які чесно потребують Windows, реальних зовнішніх програм/endpoint-ів, облікових даних, GitHub або production-soak.

## 1. Windows release acceptance — обов'язково перед Windows-релізом

Запустити на реальній Windows 10/11 x64 машині під звичайним користувачем:

- зібрати фінальний Windows app-image / MSI / EXE через наявні `package-desktop.ps1` / release-скрипти;
- виконати `windows-acceptance-harness-check.py` та Windows acceptance scripts з каталогу `tools/`;
- перевірити чисту інсталяцію, запуск, видалення та повторну інсталяцію;
- перевірити оновлення з **реальної попередньої версії**, а не зі штучно створеного пакета;
- перевірити збереження/міграцію користувацьких даних, колекцій, налаштувань, Reader state та резервних копій;
- виконати UI smoke при масштабуванні Windows **100%, 125%, 150%, 200%**;
- перевірити темну/світлу тему, системні шрифти, файлові діалоги, шляхи з кирилицею та довгі шляхи;
- виконати backup/restore на великій робочій БД під Windows і перевірити вплив Defender/антивірусу на I/O;
- окремо виконати Windows DPAPI integration test, який у фінальному Linux `verify` коректно пропущений за OS-condition.

Причина: поточна сесія працює на Linux, тому Windows MSI/EXE, DPAPI та Windows UI acceptance тут неможливо підтвердити фактично.

## 2. Реальний Calibre `ebook-convert`

Код Calibre-конвертації, capability matrix, source-aware export і запуск через `ProcessBuilder` перевірені тестами; fake `ebook-convert` підтвердив наскрізний шлях і доступність підтримуваної матриці форматів. Але в поточному середовищі **реального `ebook-convert` немає**.

Перед публічним релізом функції конвертації варто один раз перевірити з реально встановленим актуальним Calibre:

- FB2 → EPUB;
- FB2/EPUB → AZW3;
- FB2/EPUB → PDF;
- FB2/EPUB → DOCX або RTF;
- конвертацію книги з альтернативного локального artifact;
- коректне повідомлення при помилці Calibre та при неправильному шляху до `ebook-convert`.

## 3. GitHub / CI / CodeQL acceptance

Поточна копія не є Git checkout і не має `GITHUB_REPOSITORY`, токена або доступу до GitHub Actions, тому connected acceptance не виконувався.

Після заливання фінального коду в репозиторій треба:

- прогнати штатний CI на фінальному commit SHA;
- перевірити required checks;
- запустити CodeQL/security workflow;
- виконати `github-connected-acceptance.py` для фактичного репозиторію;
- за потреби виконати фінальний evidence ingest із digest-перевіркою.

## 4. Release evidence / `CURRENT-VALIDATION.md`

`external-acceptance-readiness.py` не слід закривати штучно: він очікує фактичну зовнішню acceptance-доказову базу в `docs/release/CURRENT-VALIDATION.md`.

Після Windows/GitHub acceptance треба:

1. зібрати фактичні логи/скриншоти/manifest-и;
2. оновити `docs/release/CURRENT-VALIDATION.md` реальними результатами;
3. повторно запустити `external-acceptance-readiness.py` та `v71-final-external-acceptance-check.py`.

## 5. Реальні зовнішні інтеграції

Без реальних endpoint/credentials неможливо повністю підтвердити production-взаємодію. Якщо ці функції входять у релізний сценарій, виконати smoke-test для:

- WebDAV sync на реальному сервері, включно з конфліктом і його розв'язанням;
- налаштованого AI provider;
- стороннього плагіна через Plugin SPI;
- OPDS-клієнта з іншого пристрою в локальній мережі;
- реального Kindle/Kobo/PocketBook або іншої цільової папки/пристрою експорту.

Секрети не додавати в репозиторій, release archive або тестові артефакти.

## 6. Підписування та публічний дистрибутив

Якщо застосунок розповсюджується зовнішнім користувачам:

- підписати Windows installer/App executable сертифікатом Authenticode;
- сформувати `SHA256SUMS` для публічних binary artifacts;
- перевірити SmartScreen/Defender поведінку підписаного пакета;
- для macOS, якщо вона входить у підтримувані платформи, виконати окремий build, codesign та notarization.

## 7. Рекомендований production soak — не блокує source archive

На реальній робочій машині бажано виконати 1–2-годинний smoke/soak з великою бібліотекою:

- каталог Flibusta приблизно 707 тис. записів;
- багаторазовий пошук авторів/серій/книг;
- перемикання тем і мов UK/EN/BG;
- відкривання/закривання Reader для різних форматів;
- серію завантажень та конвертацій;
- повторний запуск програми після аварійного завершення;
- контроль RAM/handles/temp-файлів.

Реальні performance-probe тести у Maven навмисно gated системними властивостями (`mhl.real.*`, `mhl.performance`, `myhomelib.*Benchmark`) і без production dataset у фінальному `verify` не запускалися.

## 8. Низькопріоритетне технічне обслуговування

Maven-тести на Java 21 показують попередження Mockito про майбутню заборону dynamic self-attach Byte Buddy agent. На поточному JDK це не є помилкою, тести проходять. До переходу на JDK, де dynamic agent loading буде заборонено за замовчуванням, варто налаштувати Mockito як явний `-javaagent` у тестовому профілі.
