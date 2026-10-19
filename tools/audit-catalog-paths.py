#!/usr/bin/env python3
"""Read-only MyHomeLib SQLite ↔ filesystem path audit.

This tool does NOT rename, delete, move or repair books. It refuses to guess
paths when a catalog points to a different collection root, and reports books
sharing one physical container so an automatic rename cannot update one row
while stranding others.

Run against a stopped-library backup, particularly if SQLite uses WAL.
"""
from __future__ import annotations

import argparse
import csv
from contextlib import closing
import os
import sqlite3
import stat
from collections import Counter
from pathlib import Path


def safe_cell(value: str) -> str:
    value = str(value or "")
    if value.lstrip().startswith(("=", "+", "-", "@")):
        return "'" + value
    return value


def clean_parts(value: str, allow_nested: bool) -> list[str] | None:
    if not value:
        return [] if allow_nested else None
    if '\x00' in value or ':' in value:
        return None
    value = value.replace('\\', '/')
    parts = value.split('/')
    if not allow_nested and len(parts) != 1:
        return None
    if any(part in ('', '.', '..') for part in parts):
        return None
    return parts


def checked_status(root: Path, folder: str, file_name: str) -> str:
    parts = clean_parts(folder, True)
    name = clean_parts(file_name, False)
    if parts is None or name is None:
        return 'НЕБЕЗПЕЧНИЙ_ШЛЯХ'
    target = root.joinpath(*(parts + name))
    try:
        current = root
        for part in parts + name:
            current = current / part
            mode = current.lstat().st_mode
            if stat.S_ISLNK(mode):
                return 'ПОСИЛАННЯ_ЗАМІСТЬ_ФАЙЛУ'
        return 'ФАЙЛ_ІСНУЄ' if stat.S_ISREG(target.lstat().st_mode) else 'НЕ_ЗВИЧАЙНИЙ_ФАЙЛ'
    except FileNotFoundError:
        return 'ФАЙЛ_НЕ_ЗНАЙДЕНО'
    except (OSError, ValueError):
        return 'ПОМИЛКА_ДОСТУПУ'


def matching_root(stored: str, selected: Path) -> bool:
    if not stored:
        return False
    # os.path.normcase handles Windows case-insensitivity on the target platform;
    # do not reinterpret a Windows path as a POSIX path when auditing on Linux.
    try:
        return os.path.normcase(os.path.normpath(stored)) == os.path.normcase(os.path.normpath(str(selected)))
    except (ValueError, OSError):
        return False


def audit(database: Path, root: Path, output: Path, limit: int = 0,
          include_ok: bool = False) -> Counter:
    if not database.is_file() or database.is_symlink():
        raise ValueError('SQLite-каталог має бути звичайним файлом')
    if not root.is_dir() or root.is_symlink():
        raise ValueError('Корінь бібліотеки має бути звичайною папкою')
    if limit < 0:
        raise ValueError('Ліміт не може бути від’ємним')
    root = root.absolute()
    if output.resolve().is_relative_to(root.resolve()) or output.is_symlink():
        raise ValueError('Вихідний CSV має бути поза бібліотекою і не бути символічним посиланням')
    counters: Counter = Counter()
    uri = database.absolute().as_uri() + '?mode=ro'
    with closing(sqlite3.connect(uri, uri=True, timeout=5)) as db:
        db.execute('PRAGMA query_only=ON')
        required = {'id', 'collection_root', 'folder', 'file_name', 'archive_entry', 'deleted', 'local'}
        fields = {row[1] for row in db.execute('PRAGMA table_info(books)')}
        if not required.issubset(fields):
            raise ValueError('Каталог не має необхідних полів books; його схему потрібно перевірити')
        # SQLite window aggregates detect a physical file with multiple logical
        # book rows, even if its archive_entry differs on each book.
        query = '''
            SELECT id, collection_root, folder, file_name, archive_entry, physical_references
              FROM (SELECT id, collection_root, folder, file_name, archive_entry,
                           COUNT(*) OVER (PARTITION BY COALESCE(collection_root, ''),
                              COALESCE(folder, ''), COALESCE(file_name, '')) physical_references
                      FROM books WHERE COALESCE(deleted,0)=0 AND COALESCE(local,0)=1)
             ORDER BY id
        '''
        if limit:
            query += ' LIMIT ' + str(limit)
        output.parent.mkdir(parents=True, exist_ok=True)
        if output.exists() and output.is_symlink():
            raise ValueError('Вихідний CSV не може бути символічним посиланням')
        with output.open('w', encoding='utf-8-sig', newline='') as out:
            writer = csv.writer(out, delimiter=';')
            writer.writerow(('Стан', 'Запис каталогу', 'Корінь у SQLite', 'Папка',
                             'Файл', 'Книг у фізичному файлі', 'Примітка'))
            for book_id, stored_root, folder, name, entry, references in db.execute(query):
                stored_root = stored_root or ''
                folder, name, entry = folder or '', name or '', entry or ''
                if not stored_root:
                    status = 'КОРІНЬ_НЕ_ВКАЗАНО'
                elif not matching_root(stored_root, root):
                    status = 'ІНШИЙ_КОРІНЬ_КОЛЕКЦІЇ'
                else:
                    status = checked_status(root, folder, name)
                note = 'Декілька книг посилаються на один фізичний файл' if references > 1 else ''
                counters[status] += 1
                if references > 1:
                    counters['СПІЛЬНИХ_ПОСИЛАНЬ'] += 1
                if include_ok or status != 'ФАЙЛ_ІСНУЄ' or references > 1:
                    writer.writerow(tuple(safe_cell(cell) for cell in (
                        status, book_id, stored_root, folder, name, references, note)))
    return counters


def main() -> None:
    parser = argparse.ArgumentParser(description='Лише аудит шляхів SQLite. Нічого не перейменовує і не виправляє.')
    parser.add_argument('--catalog', type=Path, required=True, help='Копія SQLite-каталогу Flib.db')
    parser.add_argument('--root', type=Path, required=True, help='Фактичний корінь файлів колекції')
    parser.add_argument('--output', type=Path, default=Path('catalog-path-review.csv'))
    parser.add_argument('--limit', type=int, default=0, help='Ліміт перевірених рядків, 0 — усі')
    parser.add_argument('--include-ok', action='store_true', help='Додати успішні рядки до CSV')
    args = parser.parse_args()
    try:
        counts = audit(args.catalog, args.root, args.output, args.limit, args.include_ok)
    except (ValueError, OSError, sqlite3.DatabaseError) as error:
        parser.error(str(error))
    print('Перевірено активних локальних записів: ' + str(sum(v for k, v in counts.items()
          if k != 'СПІЛЬНИХ_ПОСИЛАНЬ')))
    for status, count in counts.most_common():
        print(f'{status}: {count}')
    print(f'CSV: {args.output}. Файли та SQLite не змінено.')


if __name__ == '__main__':
    main()
