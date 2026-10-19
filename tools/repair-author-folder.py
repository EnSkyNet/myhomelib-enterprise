#!/usr/bin/env python3
"""Conservative offline repair of one already-organized author folder.

Default is preview ONLY. --apply requires a consistent SQLite backup and a
stopped application. Only one top-level folder can be renamed per invocation;
all matching books and book_artifacts references are updated in one SQLite
transaction, with a durable recovery record for a crash between rename/commit.
Never guesses name order: derives it from structured authors metadata.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import stat
import tempfile
import unicodedata
import uuid
from pathlib import Path
from contextlib import closing, contextmanager
import threading


def norm(text: str) -> str:
    return ' '.join(unicodedata.normalize('NFC', text or '').split())


# Windows interprets these names as devices, even with a file extension.
# Reject them on every host so Linux-generated repair plans remain safe on NTFS.
_RESERVED_WINDOWS_NAME = re.compile(
    r'^(?:CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|COM[1-9¹²³]|LPT[1-9¹²³])$',
    re.IGNORECASE,
)


def valid_component(text: str) -> bool:
    if (not text or text in ('.', '..') or text[-1] in '. '
            or any(c in text for c in '<>:"/\\|?*\x00')
            or any(ord(c) < 32 for c in text)):
        return False
    return _RESERVED_WINDOWS_NAME.fullmatch(text.split('.', 1)[0]) is None


def require_no_symlink_components(path: Path, role: str) -> None:
    """Reject links in *all* existing ancestors, not only in the leaf.

    Path.is_symlink() on root/backup misses e.g. /alias/library where only
    /alias is a symlink. Never resolve an untrusted path before this check.
    """
    absolute = path.absolute()
    for component in (*reversed(absolute.parents), absolute):
        try:
            mode = component.lstat().st_mode
        except FileNotFoundError:
            continue  # Remaining components may legitimately be created later.
        if stat.S_ISLNK(mode):
            raise ValueError(f'{role}: символічне посилання в шляху заборонене')


def safe_relative(folder: str, filename: str) -> Path:
    parts = folder.replace('\\', '/').split('/') if folder else []
    if any(not valid_component(x) for x in parts) or not valid_component(filename):
        raise ValueError('У каталозі є небезпечний шлях до файла')
    return Path(*parts, filename)


def matching_root(stored: str, root: Path) -> bool:
    return bool(stored) and os.path.normcase(os.path.normpath(stored)) == os.path.normcase(os.path.normpath(str(root)))


def is_within(folder: str, parent: str) -> bool:
    folder = folder.replace('\\', '/')
    return folder == parent or folder.startswith(parent + '/')


def replace_prefix(folder: str, previous: str, new: str) -> str:
    folder = folder.replace('\\', '/')
    if not is_within(folder, previous):
        raise ValueError('Несподіваний шлях у каталозі')
    return new + folder[len(previous):]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def as_safe_tree(folder: Path) -> dict[str, str]:
    if folder.is_symlink() or not folder.is_dir():
        raise ValueError('Вихідна папка має бути звичайним каталогом')
    found: dict[str, str] = {}
    for directory, dirs, files in os.walk(folder, followlinks=False):
        for child in dirs + files:
            item = Path(directory, child)
            mode = item.lstat().st_mode
            if stat.S_ISLNK(mode):
                raise ValueError('Символічні посилання в папці заборонені')
            if not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)):
                raise ValueError('Непідтримуваний тип файла у папці')
        for file in files:
            path = Path(directory, file)
            rel = path.relative_to(folder).as_posix()
            found[rel] = sha256(path)
    if not found:
        raise ValueError('Папка порожня — немає файлів для перевірки')
    return found


def columns(db: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in db.execute(f'PRAGMA table_info({table})')}


def candidate_matches(db: sqlite3.Connection, originals: list[str]) -> dict[str, set[str]]:
    """Resolve at most ten folder names in ONE pass over structured authors.

    Matching is global, not confined to books in the folder: ambiguous author
    identities elsewhere in the catalog must still block an automatic rename.
    Only proposed names for requested folders are held in memory.
    """
    lookup: dict[str, list[str]] = {}
    for original in originals:
        lookup.setdefault(norm(original).casefold(), []).append(original)
    matches: dict[str, set[str]] = {name: set() for name in originals}
    for first, middle, last in db.execute('SELECT first_name,middle_name,last_name FROM authors'):
        first, middle, last = [norm(value) for value in (first, middle, last)]
        if not first or not last:
            continue
        expected = norm(' '.join(x for x in (last, first, middle) if x))
        variants = (
            norm(' '.join(x for x in (first, middle, last) if x)),
            norm(first + ' ' + last), expected,
        )
        for variant in {v.casefold() for v in variants}:
            for original in lookup.get(variant, ()):
                matches[original].add(expected)
    return matches


def candidate(db: sqlite3.Connection, original: str, matched: set[str] | None = None) -> str:
    matches = matched if matched is not None else candidate_matches(db, [original])[original]
    if len(matches) != 1:
        raise ValueError('Ім’я автора не визначено однозначно з полів SQLite')
    result = next(iter(matches))
    if not valid_component(result):
        raise ValueError('Цільова назва містить неприпустимі символи')
    if result.casefold() == norm(original).casefold():
        raise ValueError('У папки вже правильний порядок ПІБ')
    return result


def catalog_folder_rows(db: sqlite3.Connection, table: str, fields: str, old_name: str):
    """Select an exact folder and its descendants, preserving case and SQL wildcards.

    A BINARY lexicographic prefix range can use an existing folder index; the
    previous substr(folder, ...) predicate always forced a scan. The character
    immediately after '/' is '0', so [name+'/', name+'0') is exactly the range
    for descendants regardless of the suffix text. No schema/index changes.
    """
    prefix = old_name + '/'
    upper = old_name + '0'
    return db.execute(
        f'SELECT {fields} FROM {table} '
        'WHERE folder COLLATE BINARY = ? '
        'OR (folder COLLATE BINARY >= ? AND folder COLLATE BINARY < ?)',
        (old_name, prefix, upper),
    )


# Fixed-size IN batches avoid SQLite variable limits and N+1 round trips.
# 400 is supported by old SQLite versions (which allow at least 999 variables).
SQL_BATCH_SIZE = 400


def sql_batches(identifiers):
    for offset in range(0, len(identifiers), SQL_BATCH_SIZE):
        yield identifiers[offset:offset + SQL_BATCH_SIZE]


def validate_book_authors(db: sqlite3.Connection, book_ids: list[str], target_name: str) -> None:
    """Every book must have a matching structured author; fetch in bounded groups.

    Streaming over each batch avoids a separate SELECT for every book and keeps
    memory proportional to the batch size even for very large author folders.
    An author from an unrelated book must never authorize moving this folder.
    """
    for group in sql_batches(book_ids):
        placeholders = ','.join('?' for _ in group)
        confirmed = set()
        for book_id, first, middle, last in db.execute(
                'SELECT ba.book_id,a.first_name,a.middle_name,a.last_name '
                'FROM book_authors ba JOIN authors a ON a.id = ba.author_id '
                f'WHERE ba.book_id IN ({placeholders})', group):
            formatted = norm(' '.join(x for x in (norm(last), norm(first), norm(middle)) if x))
            if formatted == target_name:
                confirmed.add(book_id)
        if len(confirmed) != len(group) or any(book_id not in confirmed for book_id in group):
            raise ValueError('Книга не пов’язана з підтвердженим автором цільової папки')


def build_plan(db: sqlite3.Connection, root: Path, old_name: str,
               matched_authors: set[str] | None = None) -> dict:
    require_no_symlink_components(root, 'Корінь бібліотеки')
    if not root.is_absolute() or root.is_symlink() or not root.is_dir():
        raise ValueError('Вкажіть існуючий абсолютний корінь без символічних посилань')
    if not valid_component(old_name):
        raise ValueError('Вкажіть назву однієї папки автора без шляху')
    source = root / old_name
    if source.is_symlink() or not source.is_dir():
        raise ValueError('Вихідна папка не існує або є символічним посиланням')
    target_name = candidate(db, old_name, matched_authors)
    target = root / target_name
    if target.exists() or target.is_symlink():
        raise ValueError('Цільова папка вже існує — об’єднання автоматично заборонено')
    if any(norm(child.name).casefold() == norm(target_name).casefold() for child in root.iterdir()):
        raise ValueError('Інша папка має таку саму назву без урахування регістру')
    required_books = {'id', 'collection_root', 'folder', 'file_name', 'local', 'deleted'}
    required_artifacts = {'artifact_id', 'collection_root', 'folder', 'file_name', 'archive_name', 'local'}
    if (not required_books.issubset(columns(db, 'books'))
            or not required_artifacts.issubset(columns(db, 'book_artifacts'))
            or not {'book_id', 'author_id'}.issubset(columns(db, 'book_authors'))):
        raise ValueError('Несумісна схема SQLite: потрібні books та book_artifacts')
    hashes = as_safe_tree(source)
    physical = set()
    book_ids = []
    artifact_ids = []
    for row in catalog_folder_rows(db, 'books',
                                   'id,collection_root,folder,file_name,local,deleted', old_name):
        item_id, stored_root, folder, filename, local, deleted = row
        if not is_within(folder or '', old_name):
            continue
        if not matching_root(stored_root or '', root):
            raise ValueError('Для книг папки вказаний інший корінь SQLite')
        if not local or deleted:
            raise ValueError('Папка містить записи видалених або віддалених книг')
        relative = safe_relative(folder, filename).relative_to(old_name).as_posix()
        physical.add(relative)
        book_ids.append(item_id)
    for row in catalog_folder_rows(db, 'book_artifacts',
                                   'artifact_id,collection_root,folder,file_name,archive_name,local', old_name):
        item_id, stored_root, folder, filename, archive_name, local = row
        if not is_within(folder or '', old_name):
            continue
        if not matching_root(stored_root or '', root) or not local:
            raise ValueError('Артефакт книги має інший корінь або не є локальним')
        if archive_name and archive_name != filename:
            raise ValueError('Неоднозначне фізичне ім’я архіву — автоматична зміна заборонена')
        physical.add(safe_relative(folder, filename).relative_to(old_name).as_posix())
        artifact_ids.append(item_id)
    if not book_ids:
        raise ValueError('У SQLite немає локальних книг у цій папці')
    # A matching folder name is not proof of authorship. Check *every* book,
    # including all members of shared physical ZIP archives, with bounded SQL.
    validate_book_authors(db, book_ids, target_name)
    if physical != set(hashes):
        absent = sorted(physical - set(hashes))
        extra = sorted(set(hashes) - physical)
        raise ValueError(f'Файлова система та SQLite не збігаються: відсутні={len(absent)}, зайві={len(extra)}')
    return dict(root=str(root), source=old_name, target=target_name,
                books=sorted(book_ids), artifacts=sorted(artifact_ids), hashes=hashes)


def sync_plan(db: sqlite3.Connection, plan: dict) -> None:
    """Synchronize exactly the approved IDs in bounded, set-based SQL batches.

    Read each batch before modifying it to validate original roots and folders.
    The UPDATE is conditional on the same prefix and identifiers; a different
    row count aborts the surrounding SQLite transaction (never commit partial
    paths). No TEMP tables or schema/index changes are required.
    """
    root = Path(plan['root'])
    source = plan['source']
    destination = plan['target']
    prefix = source + '/'
    for table, key, identifiers in (
            ('books', 'id', plan['books']),
            ('book_artifacts', 'artifact_id', plan['artifacts'])):
        for group in sql_batches(identifiers):
            placeholders = ','.join('?' for _ in group)
            rows = db.execute(
                f'SELECT {key}, collection_root, folder FROM {table} '
                f'WHERE {key} IN ({placeholders})', group).fetchall()
            if len(rows) != len(group):
                raise ValueError('Каталог змінився після перевірки — переміщення скасовано')
            checked = set()
            for record_id, collection_root, folder in rows:
                if (record_id in checked or not matching_root(collection_root or '', root)
                        or not is_within(folder or '', source)):
                    raise ValueError('Каталог змінився після перевірки — переміщення скасовано')
                checked.add(record_id)
            if checked != set(group):
                raise ValueError('Каталог змінився після перевірки — переміщення скасовано')
            # SQLite substr counts Unicode characters, just like Python len.
            # Preserve the complete nested path suffix, including its slash.
            update = db.execute(
                f'UPDATE {table} SET folder = ? || substr(folder, ?) '
                f'WHERE {key} IN ({placeholders}) '
                'AND (folder = ? OR substr(folder, 1, ?) = ?)',
                (destination, len(source) + 1, *group, source, len(prefix), prefix),
            )
            if update.rowcount != len(group):
                raise ValueError('Не вдалося синхронно оновити всі посилання SQLite')


def durable_json(path: Path, record: dict) -> None:
    require_no_symlink_components(path, 'Журнал ремонту')
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or path.is_symlink():
        raise ValueError('Журнал не може бути символічним посиланням')
    tmp = path.with_name(path.name + '.tmp')
    if tmp.exists() or tmp.is_symlink():
        raise ValueError('Тимчасовий журнал існує; потрібна ручна перевірка')
    with tmp.open('x', encoding='utf-8') as out:
        json.dump(record, out, ensure_ascii=False, indent=2)
        out.flush()
        os.fsync(out.fileno())
    os.replace(tmp, path)


def write_backup(db: sqlite3.Connection, backup: Path) -> None:
    require_no_symlink_components(backup, 'Резервна копія SQLite')
    if backup.exists() or backup.is_symlink():
        raise ValueError('Резервна копія вже існує — вкажіть новий шлях')
    backup.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(backup)) as destination:
        db.backup(destination)
        result = destination.execute('PRAGMA integrity_check').fetchone()[0]
        if result != 'ok':
            raise ValueError('Резервна копія SQLite не пройшла integrity_check')



_REPAIR_LOCKS = threading.local()


@contextmanager
def _exclusive_file_lock(lockfile: Path):
    """Nonblocking OS file lock, automatically released after process crash."""
    key = lockfile.resolve()
    held = getattr(_REPAIR_LOCKS, 'held', set())
    if key in held:
        yield
        return
    if lockfile.is_symlink() or (lockfile.exists() and not lockfile.is_file()):
        raise ValueError('Небезпечний файл блокування ремонту')
    flags = os.O_RDWR | os.O_CREAT
    if hasattr(os, 'O_NOFOLLOW'):
        flags |= os.O_NOFOLLOW
    fd = os.open(lockfile, flags, 0o600)
    try:
        with os.fdopen(fd, 'r+b') as stream:
            fd = -1
            if os.name == 'nt':
                import msvcrt
                stream.seek(0)
                if not stream.read(1):
                    stream.seek(0)
                    stream.write(b'\0')
                    stream.flush()
                stream.seek(0)
                try:
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                except OSError as error:
                    raise ValueError('Інший ремонт уже використовує цей каталог SQLite або журналів') from error
                unlock = lambda: msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                try:
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                except OSError as error:
                    raise ValueError('Інший ремонт уже використовує цей каталог SQLite або журналів') from error
                unlock = lambda: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
            held.add(key)
            _REPAIR_LOCKS.held = held
            try:
                yield
            finally:
                held.remove(key)
                unlock()
    finally:
        if fd >= 0:
            os.close(fd)


@contextmanager
def exclusive_repair_lock(journal_dir: Path, database: Path | None = None):
    """Serialize apply/batch/recovery by BOTH journal directory and catalog.

    The stable catalog-scoped lock lives in a per-user temp directory, never
    alongside books. A different journal directory does not bypass it. Locks
    are released by the OS on crash; nested batch -> single calls are safe.
    This cannot force unrelated programs to close their SQLite connections.
    """
    require_no_symlink_components(journal_dir, 'Каталог журналів')
    journal_dir.mkdir(parents=True, exist_ok=True)
    lockfiles = [journal_dir / '.myhomelib-author-repair.lock']
    if database is not None:
        lock_root = Path(tempfile.gettempdir()) / 'myhomelib-author-repair-locks'
        if lock_root.is_symlink():
            raise ValueError('Небезпечна папка блокування ремонту')
        lock_root.mkdir(mode=0o700, parents=True, exist_ok=True)
        identity = os.path.normcase(str(database.resolve())).encode('utf-8')
        lockfiles.append(lock_root / (hashlib.sha256(identity).hexdigest() + '.lock'))
    # Stable ordering prevents lock inversion when multiple repair processes
    # target different SQLite files but share the same journal directory.
    from contextlib import ExitStack
    with ExitStack() as stack:
        for lockfile in sorted(lockfiles, key=lambda path: str(path.resolve())):
            stack.enter_context(_exclusive_file_lock(lockfile))
        yield


def apply_one(database: Path, root: Path, old_name: str, backup: Path,
              journal_dir: Path, *, expected_plan: dict | None = None) -> Path:
    require_no_symlink_components(database, 'Файл SQLite')
    require_no_symlink_components(backup, 'Резервна копія SQLite')
    require_no_symlink_components(journal_dir, 'Каталог журналів')
    if (root.resolve() in backup.resolve().parents
            or journal_dir.resolve() == root.resolve()
            or root.resolve() in journal_dir.resolve().parents):
        raise ValueError('Резервна копія та журнали мають бути поза коренем бібліотеки')
    with exclusive_repair_lock(journal_dir, database):
        return _apply_one_locked(database, root, old_name, backup, journal_dir, expected_plan)


def _apply_one_locked(database: Path, root: Path, old_name: str, backup: Path,
                      journal_dir: Path, expected_plan: dict | None) -> Path:
    require_no_symlink_components(journal_dir, 'Каталог журналів')
    journal_dir.mkdir(parents=True, exist_ok=True)
    if root.resolve() in backup.resolve().parents or root.resolve() in journal_dir.resolve().parents or journal_dir.resolve() == root.resolve():
        raise ValueError('Резервна копія та журнали мають бути поза коренем бібліотеки')
    if any(journal_dir.glob('*.pending.json')):
        raise ValueError('Є незавершений ремонт — потрібне відновлення перед новим запуском')
    with closing(sqlite3.connect(database, timeout=1, isolation_level=None)) as db:
        db.execute('PRAGMA busy_timeout=1000')
        plan = build_plan(db, root, old_name)
        if expected_plan is not None and plan != expected_plan:
            raise ValueError('Затверджений план змінився до початку ремонту')
        # A different SQLite connection might write while an online backup is
        # being made. We must refuse to use a stale backup after such a write.
        catalog_version = db.execute('PRAGMA data_version').fetchone()[0]
        write_backup(db, backup)
        record_path = journal_dir / (uuid.uuid4().hex + '.pending.json')
        record = dict(plan=plan, backup=str(backup), stage='PREPARED')
        renamed = False
        committed = False
        source = root / plan['source']
        target = root / plan['target']
        try:
            db.execute('BEGIN EXCLUSIVE')
            if db.execute('PRAGMA data_version').fetchone()[0] != catalog_version:
                raise ValueError('SQLite змінено під час резервного копіювання — ремонт скасовано')
            # Guard against edits and new files after the initial preview/backup.
            if build_plan(db, root, old_name) != plan:
                raise ValueError('Вміст каталогу або файлів змінився під час підготовки')
            if target.exists() or target.is_symlink():
                raise ValueError('Цільова папка з’явилася після перевірки — зміни скасовано')
            # Only persist a recovery record once the exclusive SQLite guard
            # and final plan check have passed; no spurious pending journal on
            # a rejected stale backup or a busy database.
            durable_json(record_path, record)
            source.rename(target)  # same parent/volume, target must not exist
            renamed = True
            record['stage'] = 'FOLDER_RENAMED'
            durable_json(record_path, record)
            if as_safe_tree(target) != plan['hashes']:
                raise ValueError('Контрольні суми після перейменування не збігаються')
            sync_plan(db, plan)
            db.execute('COMMIT')
            committed = True
            record['stage'] = 'COMPLETED'
            durable_json(record_path, record)
            record_path.rename(record_path.with_suffix('.done.json'))
            return target
        except BaseException:
            if not committed and db.in_transaction:
                db.execute('ROLLBACK')
            if renamed and not committed and target.exists() and not source.exists():
                try:
                    if as_safe_tree(target) == plan['hashes']:
                        target.rename(source)
                except OSError:
                    pass  # leave durable journal for offline manual recovery
            raise


def read_folder_state(db: sqlite3.Connection, plan: dict, target: bool) -> bool:
    expected = plan['target'] if target else plan['source']
    for table, key, identifiers in (('books', 'id', plan['books']), ('book_artifacts', 'artifact_id', plan['artifacts'])):
        for ident in identifiers:
            row = db.execute(f'SELECT collection_root,folder FROM {table} WHERE {key}=?', (ident,)).fetchone()
            if row is None or not matching_root(row[0], Path(plan['root'])) or not is_within(row[1] or '', expected):
                return False
    # The journal must cover every book/artifact that currently uses this
    # folder prefix. Another unjournaled reference makes rollback unsafe.
    for table, key, identifiers in (('books', 'id', plan['books']),
                                     ('book_artifacts', 'artifact_id', plan['artifacts'])):
        current = {row[0] for row in catalog_folder_rows(db, table, key, expected)}
        if current != set(identifiers):
            return False
    return True


def validate_recovery_record(record: object) -> dict:
    """Reject malformed evidence *before* transactions or filesystem operations."""
    if not isinstance(record, dict) or record.get('stage') not in (
            'PREPARED', 'FOLDER_RENAMED', 'COMPLETED'):
        raise ValueError('Пошкоджений журнал відновлення: неприпустимий стан')
    plan = record.get('plan')
    if not isinstance(plan, dict) or not isinstance(plan.get('root'), str):
        raise ValueError('Пошкоджений журнал відновлення: немає кореня SQLite')
    if not isinstance(plan.get('source'), str) or not valid_component(plan['source']):
        raise ValueError('Пошкоджений журнал відновлення: небезпечна початкова папка')
    if not isinstance(plan.get('target'), str) or not valid_component(plan['target']):
        raise ValueError('Пошкоджений журнал відновлення: небезпечна цільова папка')
    for field, may_be_empty in (('books', False), ('artifacts', True)):
        ids = plan.get(field)
        if (not isinstance(ids, list) or (not ids and not may_be_empty)
                or any(not isinstance(item, str) or not item or not item.strip() for item in ids)
                or len(ids) != len(set(ids))):
            raise ValueError('Пошкоджений журнал відновлення: некоректні ідентифікатори')
    hashes = plan.get('hashes')
    if not isinstance(hashes, dict) or not hashes:
        raise ValueError('Пошкоджений журнал відновлення: немає контрольних сум')
    for filename, digest in hashes.items():
        if (not isinstance(filename, str)
                or not filename or any(not valid_component(part) for part in filename.split('/'))
                or not isinstance(digest, str) or re.fullmatch(r'[0-9a-fA-F]{64}', digest) is None):
            raise ValueError('Пошкоджений журнал відновлення: небезпечний файл або SHA-256')
    return plan


def recover_pending(database: Path, root: Path, journal_dir: Path) -> list[str]:
    """Reconcile only a recoverable one-folder crash without guessing/mixing DB states."""
    require_no_symlink_components(database, 'Файл SQLite')
    require_no_symlink_components(root, 'Корінь бібліотеки')
    require_no_symlink_components(journal_dir, 'Каталог журналів')
    if journal_dir.is_symlink() or not journal_dir.is_dir():
        raise ValueError('Каталог журналів не знайдено або він є посиланням')
    if (journal_dir.resolve() == root.resolve()
            or journal_dir.resolve().is_relative_to(root.resolve())):
        raise ValueError('Каталог журналів має бути поза бібліотекою')
    with exclusive_repair_lock(journal_dir, database):
        return _recover_pending_locked(database, root, journal_dir)


def _recover_pending_locked(database: Path, root: Path, journal_dir: Path) -> list[str]:
    if journal_dir.is_symlink() or not journal_dir.is_dir():
        raise ValueError('Каталог журналів не знайдено або він є посиланням')
    results = []
    records = sorted(journal_dir.glob('*.pending.json'))
    if not records:
        return ['Незавершених операцій немає']
    with closing(sqlite3.connect(database, isolation_level=None, timeout=1)) as db:
        db.execute('PRAGMA busy_timeout=1000')
        for record_path in records:
            if record_path.is_symlink():
                raise ValueError('Небезпечне символічне посилання у журналі')
            record = json.loads(record_path.read_text(encoding='utf-8'))
            plan = validate_recovery_record(record)
            if not matching_root(plan['root'], root) or not valid_component(plan['source']) or not valid_component(plan['target']):
                raise ValueError('Журнал належить іншій бібліотеці або має недопустимі шляхи')
            src, dst = root / plan['source'], root / plan['target']
            if src.exists() == dst.exists():
                raise ValueError('Неоднозначний стан папок; автоматичне відновлення заборонено')
            db.execute('BEGIN EXCLUSIVE')
            try:
                if dst.exists() and as_safe_tree(dst) != plan['hashes']:
                    raise ValueError('Файли змінилися після збою — потрібна ручна перевірка')
                if src.exists() and as_safe_tree(src) != plan['hashes']:
                    raise ValueError('Файли змінилися після збою — потрібна ручна перевірка')
                old_ok = read_folder_state(db, plan, False)
                new_ok = read_folder_state(db, plan, True)
                if src.exists() and old_ok and not new_ok:
                    status = 'ABORTED'
                elif dst.exists() and new_ok and not old_ok:
                    status = 'COMPLETED'
                elif dst.exists() and old_ok and not new_ok:
                    dst.rename(src)
                    status = 'ABORTED'
                else:
                    raise ValueError('SQLite та папки перебувають у несумісному стані; ремонт зупинено')
                db.execute('COMMIT')
            except BaseException:
                if db.in_transaction:
                    db.execute('ROLLBACK')
                raise
            record['stage'] = status
            durable_json(record_path, record)
            record_path.rename(record_path.with_suffix('.done.json' if status == 'COMPLETED' else '.aborted.json'))
            results.append(f'{plan["source"]}: {status}')
    return results


def main():
    parser = argparse.ArgumentParser(description='Безпечна перевірка й разовий ремонт папки автора в SQLite')
    parser.add_argument('--catalog', type=Path, required=True, help='SQLite-каталог закритої бібліотеки')
    parser.add_argument('--root', type=Path, required=True, help='Фактичний корінь колекції')
    parser.add_argument('--source', help='Назва однієї папки автора (крім режиму --recover)')
    parser.add_argument('--recover', action='store_true', help='Безпечно звірити та відновити незавершені журнали')
    parser.add_argument('--apply', action='store_true', help='Виконати зміну замість попереднього перегляду')
    parser.add_argument('--offline-confirmed', action='store_true', help='Підтвердження, що MyHomeLib закрито')
    parser.add_argument('--backup', type=Path, help='Новий файл резервної копії SQLite (обов’язково для --apply)')
    parser.add_argument('--journal-dir', type=Path, help='Папка для журналу відновлення (обов’язково для --apply)')
    args = parser.parse_args()
    try:
        if args.catalog.is_symlink() or not args.catalog.is_file():
            raise ValueError('SQLite-каталог не знайдено або це символічне посилання')
        root = args.root.absolute()
        # SQLite may reside in the library root, but never inside the folder
        # that is about to be renamed.
        if args.source and args.catalog.resolve().is_relative_to((root / args.source).resolve()):
            raise ValueError('SQLite-каталог міститься всередині папки для перейменування')
        if args.recover:
            if not args.offline_confirmed or args.journal_dir is None:
                raise ValueError('Для --recover потрібні --offline-confirmed та --journal-dir')
            for line in recover_pending(args.catalog, root, args.journal_dir):
                print(line)
        elif args.apply:
            if not args.source:
                raise ValueError('Вкажіть --source')
            if not args.offline_confirmed or args.backup is None or args.journal_dir is None:
                raise ValueError('Для --apply потрібні --offline-confirmed, --backup і --journal-dir')
            target = apply_one(args.catalog, root, args.source, args.backup, args.journal_dir)
            print('Перейменування завершено, усі перевірені шляхи SQLite оновлено: ' + str(target))
        else:
            if not args.source:
                raise ValueError('Вкажіть --source')
            with closing(sqlite3.connect(args.catalog.absolute().as_uri() + '?mode=ro', uri=True)) as db:
                plan = build_plan(db, root, args.source)
            print(json.dumps(dict(source=plan['source'], target=plan['target'],
                                  books=len(plan['books']), artifacts=len(plan['artifacts']),
                                  physical_files=len(plan['hashes']), action='ПЕРЕГЛЯД_БЕЗ_ЗМІН'),
                             ensure_ascii=False, indent=2))
    except (ValueError, OSError, sqlite3.Error) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
