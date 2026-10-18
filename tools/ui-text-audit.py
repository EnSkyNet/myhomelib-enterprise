#!/usr/bin/env python3
"""Read-only source audit of stable GUI translations and direct user-visible literals.

Check the packaged language catalogs and flag suspicious UI source text. Dynamic strings
are reported as review candidates, not incorrectly claimed to be all covered.
"""
from pathlib import Path
import collections
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
LANGS = ('uk', 'en', 'bg')
UI = ROOT / 'myhomelib-ui' / 'src' / 'main'
KEY = re.compile(r'\b(?:UiText|localizationService|i18n)\.(?:text|format)\(\s*"((?:ui|common)\.[\w.]+)"')
RAW = re.compile(r'\b(?:setText|setTitle|setHeaderText|showError|showWarning)\(\s*"([^"\n]{2,140})"')
DYNAMIC = ('ui.bulkedit.action.', 'ui.bulkedit.field.', 'ui.history.type.',
           'ui.integrity.finding.status.', 'ui.integrity.issue.',
           'ui.metadata.issue.', 'ui.organizer.action.', 'ui.organizer.apply.phase.',
           'ui.organizer.duplicate.', 'ui.organizer.filter.',
           'ui.organizer.recovery.state.', 'ui.reader.settings.scope.source.',
           'ui.reader.translation.language.')

catalogs = {}
errors = []
for language in LANGS:
    published = json.loads((ROOT / 'Lang' / (language + '.json')).read_text(encoding='utf-8'))['translations']
    packaged = json.loads((UI / 'resources/lang/default' / (language + '.json')).read_text(encoding='utf-8'))['translations']
    catalogs[language] = published
    if published != packaged:
        errors.append(f'{language}: packaged and editable language catalogs differ')

all_keys = set().union(*(c.keys() for c in catalogs.values()))
for language, values in catalogs.items():
    missing = all_keys - values.keys()
    if missing:
        errors.append(f'{language}: missing {len(missing)} stable keys')

used = set()
literals = []
for path in (UI / 'java').rglob('*.java'):
    source = path.read_text(encoding='utf-8')
    used.update(KEY.findall(source))
    for match in RAW.finditer(source):
        text = match.group(1)
        if text.startswith(('ui.', 'common.')) or re.fullmatch(r'[\d\s.:%()+\-—…]+', text):
            continue
        literals.append((str(path.relative_to(ROOT)), source[:match.start()].count('\n') + 1, text))

missing_used = sorted(k for k in used if k not in catalogs['uk'] and not k.startswith(DYNAMIC))
if missing_used:
    errors.append(f'{len(missing_used)} referenced keys missing in Ukrainian catalog: {missing_used[:20]}')
print(f'Languages checked: {", ".join(LANGS)}')
print(f'Localized keys: {len(all_keys)}; referenced static keys: {len(used)}')
print(f'Missing referenced stable keys: {len(missing_used)}')
print(f'Direct literal UI calls for manual review: {len(literals)}')
for file, line, value in literals[:40]:
    print(f'  {file}:{line}: {value!r}')
for error in errors:
    print('ERROR:', error, file=sys.stderr)
if errors:
    sys.exit(1)
print('Localization coverage check: PASS')
