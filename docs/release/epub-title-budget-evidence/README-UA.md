# Відтворення переповнення пам’яті через заголовки EPUB

`observed-results.json` містить зафіксовані результати виконаного раніше A/B-прогону. Це не сирий журнал: оригінальні stdout/stderr та Surefire XML не збереглися. Порівнювалися точні попередні production-класи й виправлення, наведене в `epub-title-budget.patch`. SHA-256 базового архіву та фінальних файлів наведено у `source-comparison.json`.

## Автоматичні регресії

Із кореня проєкту з JDK 21 і доступними залежностями:

```bash
mvn -B -ntp -Pproduction -pl myhomelib-infrastructure -am \
  -Dtest=EpubTitleBudgetTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Чотири тести охоплюють завеликий head title, повторення допустимого title до перевищення сумарної межі, точну межу без зміни зміщень і завеликий body heading. Вихідний код тестів входить до проєкту; жоден із них не вимкнений.

## Окрема JVM з heap 64 MiB

Потрібні скомпільовані класи Application, Infrastructure та їх runtime-залежності. `probe-classpath.txt` має містити цей classpath одним рядком, з роздільником `:` для Linux. `TitleBudgetProbe.java` — код probe, використаний у зафіксованому експерименті.

Наведений генератор створює еквівалентне навантаження: ZIP_STORED, один XHTML, title з 8 388 608 літер `t`, body `<p>x</p>` і 16 посилань spine. Він не відновлює оригінальний ZIP побайтно; часові поля та XML-обрамлення можуть відрізнятися. Запускайте з кореня проєкту:

```bash
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED

output = Path('target/title-probe')
output.mkdir(parents=True, exist_ok=True)
with ZipFile(output / 'title-stress.epub', 'w', compression=ZIP_STORED) as archive:
    archive.writestr('META-INF/container.xml',
        '<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>')
    archive.writestr('content.opf',
        '<package><manifest><item id="c" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine>'
        + '<itemref idref="c"/>' * 16 + '</spine></package>')
    archive.writestr('chapter.xhtml',
        '<html><head><title>' + 't' * (8 * 1024 * 1024)
        + '</title></head><body><p>x</p></body></html>')
PY

probe_cp="$(cat probe-classpath.txt)"
"$JAVA_HOME/bin/javac" -cp "$probe_cp" -d target/title-probe \
  docs/release/epub-title-budget-evidence/TitleBudgetProbe.java
"$JAVA_HOME/bin/java" -Xms16m -Xmx64m -XX:+UseG1GC \
  -cp "target/title-probe:$probe_cp" TitleBudgetProbe target/title-probe/title-stress.epub
```

Очікування для виправленого коду: `status=FAILED`, `retained_title_units=0`, повідомлення про safety limit заголовка та нормальне завершення JVM. Це навмисна відмова extraction, а не успішне читання EPUB.

Для A/B потрібні точні попередні `ContentDocumentBuilder`, `NormalizedTextBuffer` і `EpubContentExtractor` із базового архіву. Скомпілюйте їх разом в окремий каталог і поставте його першим у тому самому classpath. Решта production-класів, вхідний файл, JVM і параметри мають збігатися. Запускайте кожний варіант в окремій JVM; не порівнюйте різні вхідні файли. Відтворення старого варіанта навмисно може завершитися `OutOfMemoryError` у цій окремій JVM.

Peak RSS у записаному експерименті збирався на Linux для завершеної дочірньої JVM. Він охоплює весь процес, не лише Java heap. По одному запуску на варіант недостатньо для медіани чи SLA. Контрольована рання відмова не дає підстав заявляти відсоток економії RAM для успішної extraction.

## Межі загальної перевірки

Повний root `clean verify` не підтверджено. Остання завершена спроба дійшла через Infrastructure, Reader та UI до Bootstrap і зупинилася на `AppPathsLaunchDirTest`: середовище не надавало command metadata поточного процесу. Метод `ProcessExecutionSupportTest#timeoutTerminatesDescendantProcessTree` окремо виключався командою через відсутність видимості descendants. Обидва тести залишаються активними у проєкті. Фінальний root-прогін має виконуватися без виключень у повному desktop-середовищі.
