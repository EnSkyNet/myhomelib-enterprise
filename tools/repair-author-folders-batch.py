#!/usr/bin/env python3
"""Explicit, offline, bounded batch runner for the stage-4 single-folder repair.

The preview has no write side effects. An apply requires approval of the
fingerprint of *all* source plans. Every folder is then repaired with its own
SQLite backup and recovery journal; the runner stops on the first error.
There is deliberately no guessing, automatic resume, or all-library switch.
"""
from __future__ import annotations

import argparse
import csv
from contextlib import closing
import hashlib
import importlib.util
import json
import os
import shutil
from pathlib import Path
import sqlite3
import sys
import uuid

SCRIPT = Path(__file__).resolve().with_name('repair-author-folder.py')
spec = importlib.util.spec_from_file_location('myhomelib_single_author_repair', SCRIPT)
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)

MAX_FOLDERS = 10


def selected_folders(filename: Path, root: Path) -> list[str]:
    if filename.is_symlink() or not filename.is_file():
        raise ValueError('Не знайдено звичайний текстовий файл зі списком папок')
    if filename.resolve().is_relative_to(root.resolve()):
        raise ValueError('Файл зі списком папок має бути поза бібліотекою')
    items = []
    seen = set()
    for line in filename.read_text(encoding='utf-8-sig').splitlines():
        name = line.strip()
        if not name or name.startswith('#'):
            continue
        if not repair.valid_component(name):
            raise ValueError('Список містить небезпечну назву папки')
        marker = repair.norm(name).casefold()
        if marker in seen:
            raise ValueError('Список містить повторну назву папки')
        seen.add(marker)
        items.append(name)
        if len(items) > MAX_FOLDERS:
            raise ValueError(f'За один запуск дозволено не більш ніж {MAX_FOLDERS} папок')
    if not items:
        raise ValueError('Список порожній; вкажіть хоча б одну папку')
    return items


def selected_audit_rows(filename: Path, root: Path, names: list[str]) -> dict[str, tuple[str, int]]:
    """Read ONLY chosen proposal rows from the JavaFX localized CSV report.

    The CSV cannot authorize any mutation: preflight independently rechecks
    authors, targets, books and SHA-256 against the current SQLite and disk.
    """
    if filename.is_symlink() or not filename.is_file() or filename.stat().st_size > 64 * 1024 * 1024:
        raise ValueError('Не знайдено коректного CSV-звіту (максимум 64 МБ)')
    if filename.resolve().is_relative_to(root.resolve()):
        raise ValueError('CSV-звіт має бути поза бібліотекою')
    catalogs = Path(__file__).resolve().parents[1] / 'myhomelib-ui' / 'src' / 'main' / 'resources' / 'lang' / 'default'
    languages = []
    for language in ('uk', 'en', 'bg'):
        with (catalogs / f'{language}.json').open(encoding='utf-8') as reader:
            strings = json.load(reader)['translations']
        languages.append((tuple(strings['ui.organizer.author_batch.column.' + key]
                                for key in ('current', 'proposed', 'books', 'status')),
                          strings['ui.organizer.author_preview.status.proposal']))
    wanted = {repair.norm(name).casefold(): name for name in names}
    selected = {}
    with filename.open('r', encoding='utf-8-sig', newline='') as handle:
        reader = csv.reader(handle, delimiter=';', strict=True)
        headers = tuple(next(reader, ()))
        matched_language = next((status for expected, status in languages if headers == expected), None)
        if matched_language is None:
            raise ValueError('Непідтримуваний заголовок CSV: потрібен оригінальний звіт MyHomeLib')
        for line in reader:
            if len(line) != 4:
                raise ValueError('Пошкоджений рядок CSV — очікується чотири колонки')
            name, proposed, count, status = line
            original = wanted.get(repair.norm(name).casefold())
            if original is None:
                continue
            if original != name or original in selected:
                raise ValueError('CSV містить повторну або змінену назву вибраної папки')
            if status != matched_language:
                raise ValueError('Вибрана папка не має статусу можливого виправлення в CSV')
            try:
                books = int(count)
            except ValueError as error:
                raise ValueError('У CSV вказано некоректну кількість книг') from error
            if books < 1 or not repair.valid_component(proposed):
                raise ValueError('У CSV вказано некоректну запропоновану назву')
            selected[original] = (proposed, books)
    if len(selected) != len(names):
        raise ValueError('CSV не містить усіх явно вибраних папок')
    return selected


def validate_audit_rows(plans: list[dict], selected: dict[str, tuple[str, int]]) -> None:
    for plan in plans:
        proposed, books = selected[plan['source']]
        if proposed != plan['target'] or books != len(plan['books']):
            raise ValueError('CSV застарів або дані змінено — створіть новий звіт')


def preflight(dbfile: Path, root: Path, names: list[str]) -> tuple[list[dict], str]:
    """Precheck all candidates, including file checksums, before any change."""
    repair.require_no_symlink_components(dbfile, 'Файл SQLite')
    if dbfile.is_symlink() or not dbfile.is_file():
        raise ValueError('SQLite-каталог не знайдено або це символічне посилання')
    if len(names) < 1 or len(names) > MAX_FOLDERS:
        raise ValueError('Кількість папок не відповідає обмеженню')
    with closing(sqlite3.connect(dbfile.absolute().as_uri() + '?mode=ro', uri=True)) as db:
        db.execute('PRAGMA query_only=ON')
        # Expensive global author ambiguity checks are shared across all
        # selected folders rather than scanning 170k authors ten times.
        matched = repair.candidate_matches(db, names)
        plans = [repair.build_plan(db, root, name, matched[name]) for name in names]
    # A source must not become another source's target, and no two sources
    # may propose the same target (also case-insensitively on Windows).
    src_keys = {repair.norm(p['source']).casefold() for p in plans}
    target_keys = [repair.norm(p['target']).casefold() for p in plans]
    if len(target_keys) != len(set(target_keys)) or any(x in src_keys for x in target_keys):
        raise ValueError('У вибраних папок перетинаються цільові назви')
    encoded = json.dumps(plans, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
    return plans, hashlib.sha256(encoded).hexdigest()


def do_batch(dbfile: Path, root: Path, names: list[str], approval: str,
             backup_dir: Path, journal_dir: Path, executor=None,
             audit_csv: Path | None = None) -> list[tuple[str, str]]:
    """Apply one at a time; a failure leaves completed folders intact.

    Recovery is exclusively through the original tool's --recover. Never
    automatically advance past a failed or interrupted folder.
    """
    if not approval or len(approval) != 64 or any(c not in '0123456789abcdef' for c in approval):
        raise ValueError('Потрібен контрольний код SHA-256 із нового попереднього перегляду')
    for directory in (backup_dir, journal_dir):
        repair.require_no_symlink_components(directory, 'Каталог резервних копій або журналів')
        if directory.is_symlink() or directory.resolve() == root.resolve() or directory.resolve().is_relative_to(root.resolve()):
            raise ValueError('Резервні копії та журнали мають бути поза бібліотекою')
    with repair.exclusive_repair_lock(journal_dir, dbfile):
        return _do_batch_locked(dbfile, root, names, approval, backup_dir, journal_dir,
                                executor=executor, audit_csv=audit_csv)


def _do_batch_locked(dbfile: Path, root: Path, names: list[str], approval: str,
                     backup_dir: Path, journal_dir: Path, executor=None,
                     audit_csv: Path | None = None) -> list[tuple[str, str]]:
    if journal_dir.exists() and any(journal_dir.glob('*.pending.json')):
        raise ValueError('Є незавершений ремонт — спочатку виконайте --recover')
    audit_rows = selected_audit_rows(audit_csv, root, names) if audit_csv else None
    plans, signature = preflight(dbfile, root, names)
    if audit_rows is not None:
        validate_audit_rows(plans, audit_rows)
    if approval != signature:
        raise ValueError('План змінився після перегляду; зробіть новий перегляд і підтвердження')
    if backup_dir.resolve() == journal_dir.resolve():
        raise ValueError('Резервні копії й журнали повинні зберігатися в різних каталогах')
    # Each folder creates a separate full SQLite backup. Refuse the whole
    # batch early if the target volume cannot safely hold all of them.
    nearest = backup_dir
    while not nearest.exists():
        if nearest == nearest.parent:
            raise ValueError('Не вдалося визначити диск резервного копіювання')
        nearest = nearest.parent
    free_bytes = shutil.disk_usage(nearest).free
    database_bytes = dbfile.stat().st_size
    safety_margin = max(32 * 1024 * 1024, min(256 * 1024 * 1024, database_bytes // 20))
    required_bytes = database_bytes * len(plans) + safety_margin
    if free_bytes < required_bytes:
        raise ValueError('Недостатньо вільного місця для всіх резервних копій SQLite: '
                         f'потрібно щонайменше {required_bytes // (1024 * 1024) + 1} МБ')
    backup_dir.mkdir(parents=True, exist_ok=True)
    journal_dir.mkdir(parents=True, exist_ok=True)
    call = executor or repair.apply_one
    completed = []
    for idx, plan in enumerate(plans, 1):
        backup = backup_dir / (f'author-repair-{idx:02d}-{uuid.uuid4().hex}.sqlite')
        print(f'[{idx}/{len(plans)}] Перевірка й виправлення: {plan["source"]}', flush=True)
        try:
            if executor is None:
                # Re-check the *whole approved per-folder plan* inside the
                # SQLite transaction. A changed later folder must not inherit
                # consent from a previously approved batch fingerprint.
                call(dbfile, root, plan['source'], backup, journal_dir,
                     expected_plan=plan)
            else:
                call(dbfile, root, plan['source'], backup, journal_dir)
        except BaseException:
            print(f'Зупинено після {len(completed)} завершених змін. '
                  'Повторне застосування заборонено до перевірки журналів відновлення.',
                  file=sys.stderr, flush=True)
            raise
        completed.append((plan['source'], plan['target']))
        print(f'Завершено: {plan["source"]} → {plan["target"]}', flush=True)
    return completed


def main() -> None:
    parser = argparse.ArgumentParser(description='Пакетна перевірка та контрольований ремонт до 10 папок авторів')
    parser.add_argument('--catalog', required=True, type=Path, help='SQLite закритої колекції')
    parser.add_argument('--root', required=True, type=Path, help='Папка з каталогами авторів')
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument('--sources-file', type=Path, help='UTF-8: точна назва однієї папки на рядок')
    selection.add_argument('--select-folder', action='append', help='Явно вибрана папка (до 10, можна повторити)')
    parser.add_argument('--audit-csv', type=Path, help='Додаткова перевірка вибраних папок за оригінальним CSV-звітом JavaFX')
    parser.add_argument('--apply', action='store_true', help='Виконати попередньо перевірений план')
    parser.add_argument('--offline-confirmed', action='store_true', help='Підтвердження зупинки MyHomeLib')
    parser.add_argument('--approve-sha256', help='Контрольний код SHA-256 саме цього плану')
    parser.add_argument('--backup-dir', type=Path, help='Окрема папка з SQLite-бекапами для кожної зміни')
    parser.add_argument('--journal-dir', type=Path, help='Окрема папка журналів відновлення')
    args = parser.parse_args()
    try:
        root = args.root.absolute()
        if args.sources_file:
            names = selected_folders(args.sources_file, root)
        else:
            names = args.select_folder
            if not 1 <= len(names) <= MAX_FOLDERS or any(not repair.valid_component(n) for n in names) or len({repair.norm(n).casefold() for n in names}) != len(names):
                raise ValueError('Виберіть від 1 до 10 різних безпечних папок')
        if args.apply:
            if not args.offline_confirmed or not args.approve_sha256 or not args.backup_dir or not args.journal_dir:
                raise ValueError('Для зміни потрібні --offline-confirmed, --approve-sha256, --backup-dir і --journal-dir')
            completed = do_batch(args.catalog, root, names, args.approve_sha256, args.backup_dir, args.journal_dir, audit_csv=args.audit_csv)
            print(f'Підсумок: безпечно перейменовано {len(completed)} папок; усі записи SQLite синхронізовано')
        else:
            audit_rows = selected_audit_rows(args.audit_csv, root, names) if args.audit_csv else None
            plans, signature = preflight(args.catalog, root, names)
            if audit_rows is not None:
                validate_audit_rows(plans, audit_rows)
            for i, p in enumerate(plans, 1):
                print(f'{i}. {p["source"]} → {p["target"]}: '
                      f'{len(p["books"])} книг, {len(p["hashes"])} файлів')
            print('Попередній перегляд: файли та SQLite не змінені')
            print(f'Код підтвердження SHA-256: {signature}')
    except (ValueError, OSError, sqlite3.Error, csv.Error, UnicodeError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
