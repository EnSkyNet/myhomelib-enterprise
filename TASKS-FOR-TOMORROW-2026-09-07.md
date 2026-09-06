# MyHomeLib 7.1 RC3 — завдання на завтра

Дата handoff: 06.09.2026
Почати роботу: 07.09.2026
Базовий checkpoint: Iteration 22 — Windows host/session binding WIP

## Поточний стан

Локальна розробка і hardening 7.1 Final завершені настільки, наскільки це можливо без реальних зовнішніх середовищ.

Незакриті задачі:

- MHL-010 — GitHub / PR CI acceptance
- MHL-011 — Windows DPI 100/125/150/200%
- MHL-012 — Windows installer + portable + desktop acceptance
- MHL-017 — release SBOM live evidence
- MHL-018 — Dependency-Check live evidence
- MHL-019 — CodeQL/SAST live evidence

Не переводити ці задачі у «Виконано» без реального PASS.

## Завдання 1 — GitHub live acceptance

1. Взяти exact candidate SHA, який планується як 7.1 release candidate.
2. Переконатися, що CodeQL успішно проаналізував цей exact SHA у default branch.
3. Запустити `CI Release` на цьому SHA.
4. Запустити `GitHub connected acceptance` на тому самому SHA / release run.
5. Отримати `Overall: PASS`.
6. Зберегти:
   - CI Release run URL/ID;
   - GitHub connected acceptance run URL/ID;
   - non-expired `github-connected-acceptance-<run>-<attempt>` artifact.
7. Перевірити, що artifact містить:
   - candidate MSI;
   - candidate EXE;
   - candidate portable ZIP;
   - `candidate-windows.sha256`;
   - `acceptance-harness.sha256`;
   - JSON + Markdown evidence.

## Завдання 2 — одна Windows acceptance session

Використати чистий disposable Windows profile:

- standard / non-elevated user;
- exact candidate checkout;
- одна й та сама машина;
- один і той самий Windows account;
- реальний MSI попередньої версії.

Запустити ОДИН раз:

```powershell
.\tools\v71-windows-acceptance-start.ps1 `
  -Repo OWNER/REPO `
  -AcceptanceRunId <github-connected-acceptance-run-id> `
  -PreviousMsi C:\path\to\MyHomeLib-<previous>.msi `
  -PreviousVersion <previous-version>
```

Не перезапускати цей command після початку DPI acceptance: новий запуск створить новий `acceptanceSessionId` і зробить попередні DPI evidence непридатними.

Команда повинна:

- digest-verify GitHub acceptance artifact;
- перевірити candidate-bound acceptance harness;
- створити `windows-host-binding.json`;
- підтвердити standard/non-elevated user;
- пройти real previous -> current MSI lifecycle;
- пройти portable Unicode/isolation smoke;
- пройти real desktop acceptance точного candidate EXE.

## Завдання 3 — DPI acceptance на тому самому host/user/session

На тій самій машині й тому самому Windows account виконати:

```powershell
.\tools\windows-ui-acceptance.ps1 -Scale 100
.\tools\windows-ui-acceptance.ps1 -Scale 125
.\tools\windows-ui-acceptance.ps1 -Scale 150
.\tools\windows-ui-acceptance.ps1 -Scale 200
```

Вимоги:

- кожен P4 item = PASS;
- кожен PASS має унікальний видимий PNG;
- жодного clipping / overlap / geometry defect на критичних екранах;
- усі 4 DPI-звіти повинні мати однакові `acceptanceSessionId`, host fingerprint і user fingerprint.

Якщо Windows потребує sign-out/restart при зміні scaling — не міняти машину, Windows account, candidate checkout або `target\windows-host-binding`.

## Завдання 4 — фінальний consolidated PASS

Після GitHub + Windows + DPI evidence виконати:

```powershell
.\tools\v71-finalize-external-acceptance.ps1 `
  -GitHubEvidenceRoot target\github-connected-acceptance
```

Обов'язковий результат:

```text
MyHomeLib 7.1 final external evidence: PASS
```

Зберегти:

- `target/myhomelib-7.1-final-external-evidence.zip`;
- `target/myhomelib-7.1-final-external-evidence.zip.sha256`;
- `target/v71-final-external-acceptance/v71-final-external-acceptance.json`.

## Завдання 5 — закриття backlog

Тільки після consolidated PASS перевести у «Виконано»:

- MHL-010
- MHL-011
- MHL-012
- MHL-017
- MHL-018
- MHL-019

До кожної задачі додати фактичні URL/ID live GitHub runs та фінальний reviewer ZIP/hash.

## Завдання 6 — фінальний 7.1 non-WIP checkpoint

Після закриття шести external items:

1. Запустити нормальні clean production release gates на тому самому candidate.
2. Оновити backlog/dashboard до 22/22 задач 7.1 Final = «Виконано».
3. Створити фінальний non-WIP checkpoint 7.1.
4. Створити SHA-256.
5. Перевірити ZIP integrity.
6. Не включати `target/`, `__pycache__`, `.pyc`, build logs та тимчасові файли.

## Якщо live acceptance знайде дефект

Не обходити gate і не змінювати evidence вручну.

- GitHub/CodeQL/CVE failure -> виправити причину, створити новий candidate SHA, повторити весь candidate-bound flow.
- MSI/portable/desktop failure -> виправити defect, новий candidate SHA, повторити GitHub + Windows acceptance.
- DPI clipping/overlap -> виправити UI, новий candidate SHA, повторити GitHub acceptance і Windows session для нового candidate.

## Джерело істини для продовження

Використовувати:

- `CONTINUATION-ITERATION-22-FINAL-LIVE-ACCEPTANCE.md`
- `ITERATION-22-WINDOWS-HOST-SESSION-BINDING-WIP.md`
- `MYHOMELIB-RELEASE.md`
- `MyHomeLib_Technical_Backlog_7.1RC3_to_8.0_2026-09-06_reconciled_wip9.xlsx`

Не повертатися до старіших Iteration 16-21 runbook як до основного сценарію, якщо Iteration 22 доступна.
