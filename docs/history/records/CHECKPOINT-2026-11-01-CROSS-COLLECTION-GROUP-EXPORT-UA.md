# Контрольна точка — portable metabib export між колекціями

**Дата:** 1 листопада 2026 року  
**Репозиторій:** `EnSkyNet/myhomelib-enterprise`  
**Пов’язана задача:** #132 — V34 P2: complete MyHomeLib 2.7.0 portable metabib group export with cross-collection support  
**PR:** #160 — відкрита чернетка; не зливати до завершення зовнішнього приймання.

## Джерело коду

- Актуальна основа `master`: `c069f0e02af921d8b1897cb41bb371a5ad39842f`.
- Робоча гілка: `feat/v34-cross-collection-group-metabib-export-20261101`.
- Перевірений HEAD PR #160: `abc6971b0a9a3b3e155e32d8f158d5d46083cb5f`.
- Порівняння з `master`: **81 коміт попереду, 0 позаду**.
- PR #160: OPEN, DRAFT, mergeable; автоматично не зливати.
- Старі ревізії/прогони до `abc6971` не вважати остаточним доказом для поточного HEAD.

## Реалізовано

- Дія експорту групи доступна з контекстного меню груп.
- Книги збираються з усіх зареєстрованих SQLite-колекцій, де знайдено однойменну групу.
- Збіг назв нормалізується через NFC, згортання Unicode-пробілів і порівняння без урахування регістру; неоднозначний збіг завершується помилкою, а не довільним вибором.
- Для кожної колекції використовується окрема read-only SQLite-сесія зі snapshot-перевіркою; активна колекція не перемикається, бази не модифікуються.
- Експортуються лише доступні локальні артефакти. Вміст файлів копіюється потоково, без мережевого завантаження, конвертації чи переписування вихідних файлів.
- ZIP-пакети обмежені 1 000 книгами кожен; JSONL зберігає бібліографічні claims, походження колекції, LibID, локальні ідентичності та конфліктні назви/ієрархії жанрів.
- Локальний ID книги scoped за ID вихідної колекції: дві незалежні бази можуть містити однаковий UUID книги без колізії ідентичностей.
- Додано інтеграційний тест із двома реальними SQLite-базами, однаковими локальними UUID книг, різними локальними ID груп, однаковими назвами груп, окремими ZIP та перевіркою імпорту після переміщення пакета.
- Додано регресії для Unicode/пробілів у назвах груп, колізій локальних ID, Windows-шляху довшого за MAX_PATH, локальних альтернативних артефактів, скасування та недоступних джерел.
- Опис поведінки додано до `docs/current/FEATURES.md`; цей checkpoint синхронізовано з актуальними результатами CI.

## Перевірки поточного HEAD `abc6971`

| Перевірка | Workflow run | Результат |
|---|---:|---|
| PR CI | `41307474595` | PASS |
| CodeQL Java | `41307474563` | PASS |
| Windows isolated full root Maven clean verify | `41307474562` | PASS |
| V34 SHA-bound Windows test-only Portable | `41307474566` | PASS |

Повний Windows Maven-прогін:

- Windows Server 2025, Java 21, Maven 3.9.16.
- Maven reactor: 21 модуль; `BUILD SUCCESS`; час 7 хв 02 с.
- Surefire звіти: 742 файли.
- Тестів: **2 884**; помилок: **0**; збоїв: **0**; пропущено: **29**.
- `SqliteCollectionBookReadAdapterTest`: 5/5.
- `GroupMetabibExportServiceTest`: 19/19.
- `CrossCollectionGroupExportSqliteIntegrationTest`: 1/1.
- `GroupMetabibExportRoundTripE2ETest`: 1/1.
- У цільових тестах експорту пропусків немає.

Повний журнал і звіти:

- Artifact: `windows-full-verify-41307474562-1`.
- Artifact ID: `13083718724`; розмір ZIP: 7 543 253 байти.
- Локально перевірений SHA-256 ZIP звітів: `a966d08994c9faf4b5b2566f778ff9952ed92f6b729ca3599231ed2657de4e03`.
- `result.txt`: `exit=0`; `maven.log`: `BUILD SUCCESS`.

## SHA-bound Windows test-only Portable

- Artifact workflow: `41307474566`; artifact ID: `13084601444`.
- Кандидат у manifest: `abc6971b0a9a3b3e155e32d8f158d5d46083cb5f`.
- Manifest: `overall=PASS`, `platform=windows`, `distFileCount=1`, `sourceFileCount=3389`.
- Внутрішній пакет: `MyHomeLib-R18-8.0.0-portable-windows-x64.zip`.
- Розмір внутрішнього пакета: 178 670 575 байтів.
- SHA-256 внутрішнього пакета: `7ee2cba8e958a52a8ac1ff9e035ac2a4fbe9dae15ed90a70f660798a48d8de41`.
- ZIP-структура перевірена повністю; пошкоджених записів немає; launcher, bundled runtime та portable data присутні.
- Smoke: Windows Server 2025 Datacenter, build 26100; native EXE, CMD і VBS launchers завершилися з кодом 0; Unicode extract/home/working paths пройшли; сторонніх записів у synthetic home/profile/working directory не виявлено.
- Зовнішній artifact-wrapper: `MyHomeLib-V34-test-only-Windows-Portable-abc6971b.zip`, 177 802 174 байти, SHA-256 `8c1dbd232596298c4dd2e4f20dd253b1f4f1ccc786046aa25358959681dc78b3`.

## Що ще НЕ підтверджено

- Це **test-only candidate**, а не офіційний реліз.
- Smoke виконано на GitHub-hosted Windows Server 2025, а не на фізичній Windows 11 користувача.
- Не проведено зовнішнє приймання на Windows 11 із 4 ГБ RAM: запуск на цільовій машині, реальна бібліотека, ручна перевірка експорту/переміщення/імпорту та ресурсного профілю.
- PR #160 залишається Draft; issue #132 залишається відкритим. Не зливати до завершення зовнішнього приймання.

## Наступні кроки

1. Передати test-only Portable для ручного приймання на цільовій Windows-машині.
2. Перевірити запуск, наявні локальні книги, групи з кількох колекцій, переміщення пакета й повторний імпорт через штатний імпортер.
3. Перевірити пам’ять/процеси та роботу на реальному каталозі; зафіксувати ОС, обсяг RAM, логи й SHA-256 пакета.
4. Лише після цього оновити issue #132 і розглядати зняття Draft/merge PR #160.
