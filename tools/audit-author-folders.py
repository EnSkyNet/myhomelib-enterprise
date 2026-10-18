#!/usr/bin/env python3
"""Read-only report of author folders; never rename books or modify SQLite.

With --catalog, prefer structured authors.first_name/middle_name/last_name from
SQLite. A proposal is *only* confirmed when the catalog yields a unique full
family-first target and that directory does not already exist.
Without the catalog, the existing common-given-name heuristic is diagnostic
only; it never confers permission to rename or move files.
"""

from __future__ import annotations

import argparse
import csv
from contextlib import closing
import re
import sqlite3
import unicodedata
from collections import defaultdict
from pathlib import Path


def normalized(value: str) -> str:
    return " ".join(unicodedata.normalize("NFC", value).split())


def key(value: str) -> str:
    return normalized(value).casefold()


def safe_component(value: str) -> bool:
    return bool(value) and not any(ch in value for ch in '<>:"/\\|?*\x00') and not any(
        ord(ch) < 32 for ch in value
    ) and value[-1] not in ' .'


def catalog_names(database: Path) -> dict[str, set[str]]:
    if not database.is_file() or database.is_symlink():
        raise ValueError('Файл SQLite не знайдено або це символічне посилання')
    # Read-only SQLite connection, including existing WAL state when present.
    # Run on a consistent stopped-library backup for the most reliable result.
    uri = database.absolute().as_uri() + '?mode=ro'
    result: dict[str, set[str]] = defaultdict(set)
    with closing(sqlite3.connect(uri, uri=True, timeout=10)) as connection:
        connection.execute('PRAGMA query_only=ON')
        for first, middle, last in connection.execute(
            'SELECT first_name, middle_name, last_name FROM authors'
        ):
            first, middle, last = [normalized(value or '') for value in (first, middle, last)]
            if not first or not last:
                continue
            expected = normalized(' '.join(filter(None, (last, first, middle))))
            if not safe_component(expected):
                continue
            variants = {
                normalized(' '.join(filter(None, (first, middle, last)))),
                normalized(' '.join((first, last))),
                expected,
            }
            for variant in variants:
                result[key(variant)].add(expected)
    return result


def common_names() -> set[str]:
    source = (Path(__file__).resolve().parents[1]
              / 'myhomelib-library-organizer/src/main/java/com/myhomelibcorp/organizer/metadata/InferredAuthorNameOrder.java')
    if not source.is_file():
        return set()
    text = source.read_text('utf-8')
    body = text.split('GIVEN_NAMES = Set.of(', 1)[1].split(');', 1)[0]
    return {word.casefold() for word in re.findall(r'"([^\"]+)"', body)}


def fallback_proposal(folder: str, given: set[str]) -> tuple[str, str, str]:
    parts = normalized(folder).split()
    if len(parts) == 1:
        return 'ОДНЕ_ІМЯ_АБО_ПСЕВДОНІМ', folder, 'Немає достовірного поділу на ім’я та прізвище'
    if len(parts) not in (2, 3):
        return 'ПОТРЕБУЄ_ПЕРЕВІРКИ', folder, 'Неструктуровані метадані'
    patronymic = ('ович', 'евич', 'йович', 'івич', 'ич', 'овна', 'евна', 'івна', 'ївна', 'ична', 'инична')
    if (parts[0].casefold() in given and parts[-1].casefold() not in given
            and (len(parts) == 2 or parts[1].casefold().endswith(patronymic))):
        proposal = ' '.join([parts[-1], parts[0]] + ([parts[1]] if len(parts) == 3 else []))
        return 'НЕПІДТВЕРДЖЕНЕ_ПРИПУЩЕННЯ', proposal, 'Тільки словник поширених імен'
    if (parts[0].casefold() not in given and parts[1].casefold() in given
            and (len(parts) == 2 or parts[2].casefold().endswith(patronymic))):
        return 'ЙМОВІРНО_ПРАВИЛЬНО', folder, 'Тільки словник поширених імен'
    return 'ПОТРЕБУЄ_ПЕРЕВІРКИ', folder, 'Порядок ПІБ невідомий'


def audit_folders(directory: Path, catalog: dict[str, set[str]] | None,
                  given: set[str]) -> list[tuple[str, str, str, str]]:
    folders = [f for f in directory.iterdir() if f.is_dir() and not f.is_symlink()]
    folders.sort(key=lambda f: f.name.casefold())
    names = {key(f.name) for f in folders}
    rows = []
    for folder in folders:
        original = folder.name
        possible = catalog.get(key(original), set()) if catalog is not None else set()
        if len(possible) > 1:
            status, proposal, reason = ('КОНФЛІКТ_АВТОРІВ', original,
                                        'Кілька різних авторів відповідають назві папки')
        elif len(possible) == 1:
            proposal = next(iter(possible))
            if key(proposal) == key(original):
                status, reason = 'ПІБ_ЗБІГАЄТЬСЯ_З_ІМЕНЕМ_В_SQLITE', 'Збіг імені без перевірки прив’язки книг до папки'
            elif key(proposal) in names or (directory / proposal).exists():
                status, reason = 'ЦІЛЬОВА_ПАПКА_ІСНУЄ', 'Збіг імені в SQLite; прив’язка книг не перевірена'
            else:
                status, reason = 'КАНДИДАТ_ЗА_ПІБ_У_SQLITE', 'Ім’я є в SQLite, але належність книг до цієї папки не перевірена'
        else:
            status, proposal, reason = fallback_proposal(original, given)
        rows.append((status, original, proposal, reason))
    return rows


def safe_csv(value: str) -> str:
    # CSV quotes alone do not prevent Excel/Calc formula injection.
    # Only the *report* is escaped; real directory names remain untouched.
    stripped = value.lstrip()
    if stripped and stripped[0] in '=+-@':
        return "'" + value
    return value


def main() -> None:
    parser = argparse.ArgumentParser(description='Аудит порядку ПІБ у папках (лише звіт, без зміни файлів).')
    parser.add_argument('directory', type=Path, help='Папка бібліотеки з каталогами авторів')
    parser.add_argument('--catalog', type=Path, help='SQLite-каталог із структурованими полями авторів')
    parser.add_argument('--output', type=Path, default=Path('author-folder-review.csv'))
    args = parser.parse_args()
    if not args.directory.is_dir():
        parser.error('Папку бібліотеки не знайдено')
    try:
        catalog = catalog_names(args.catalog) if args.catalog else None
    except (OSError, ValueError, sqlite3.Error) as error:
        parser.error(f'Не вдалося прочитати SQLite-каталог: {error}')
    rows = audit_folders(args.directory, catalog, common_names())
    # Diagnostic reports must never be written into the collection being audited.
    if args.output.resolve().is_relative_to(args.directory.resolve()) or args.output.is_symlink():
        parser.error('CSV-звіт має бути поза бібліотекою і не бути символічним посиланням')
    with args.output.open('w', encoding='utf-8-sig', newline='') as output:
        writer = csv.writer(output, delimiter=';')
        writer.writerow(('Стан', 'Поточна папка', 'Запропонована назва', 'Підстава'))
        writer.writerows(tuple(safe_csv(cell) for cell in row) for row in rows)
    print(f'Перевірено папок: {len(rows)}. Створено звіт: {args.output}. '
          'Книги, база даних і папки не змінювалися.')


if __name__ == '__main__':
    main()
