# MyHomeLib — наступні задачі після V34 External Test R13

**Дата handoff:** 2026-10-04
**База:** exact R13 regression candidate
**Фокус:** підтвердити на Windows, що виправлено саме три реальні проблеми користувача: primary projection після conversion, час FB2→EPUB route та EPUB Book Details.

## P0 — backup і V68 repair на реальній R12 data

1. Перед першим R13 запуском зробити повну копію portable `data/`.
2. Запустити R13 на копії даних і дочекатися Flyway migration V68.
3. Для книги, яку R12 уже перевів на converted EPUB primary, перевірити:
   - primary знову original FB2/FB2-in-ZIP;
   - conversion artifact не втрачений і лишився secondary;
   - reopening/restart не змінює preferred artifact.
4. Якщо V68 не відновив конкретний запис — зберегти SQLite DB/мінімальний sanitized row evidence до ручного виправлення.

## P0 — FB2 → EPUB на bundled FBC

На supplied `729586 Демон. Пожиратель душ #01-18.fb2.zip`:

1. запустити conversion у R13 Portable;
2. зафіксувати provider/route — normal portable path має використовувати bundled `fbc.exe`, не Calibre лише через відсутність FBC;
3. зафіксувати wall-clock elapsed;
4. після success original FB2 має лишитися primary;
5. EPUB — secondary artifact;
6. повторна conversion в EPUB не створює duplicate artifact row;
7. restart програми не змінює primary.

Не встановлювати цільове число секунд без Windows evidence. Якщо bundled FBC route усе ще аномально повільний — профілювати process/runtime на реальній машині.

## P0 — EPUB Book Details

1. Відкрити converted EPUB як artifact/reader input.
2. Перевірити full metadata/text parse.
3. Якщо XHTML/spine проблемний, Book Details має показати OPF metadata fallback із warning.
4. Якщо конкретний generated EPUB все одно не читається — зберегти сам EPUB як regression corpus; не заміняти його synthetic fixture.

## P0 — exact R13 full root build у середовищі без 5-хвилинного tool limit

Запустити:

```text
xvfb-run -a bash ./tools/invoke-maven.sh -B -o -Dmaven.repo.local=<offline-repo> clean verify
```

Acceptance: `BUILD SUCCESS`, 0 failures/errors, exact aggregate задокументований у `CURRENT-VALIDATION` та R13 validation. Локальні R13 attempts у поточному контейнері були terminated зовнішнім execution limit до reactor summary, тому цей gate лишається OPEN.

## Далі

Після закриття трьох користувацьких regression scenarios повернутися до R11/R12 external backlog: Windows updater/installer/portable/DPAPI/DPI/rollback, physical Kobo, real Readwise, unrestricted PWA, real CBR/CB7 corpus, Windows 1–2 h memory soak та connected CI/SBOM/SCA/CodeQL.
