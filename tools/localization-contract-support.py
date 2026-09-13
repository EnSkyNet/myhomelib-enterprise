"""Resolve stable localization keys used by a source file for legacy semantic UI checks."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def localized_source(source: str) -> str:
    catalogs = [json.loads((ROOT / 'Lang' / (language + '.json')).read_text(encoding='utf-8'))['translations']
                for language in ('uk', 'en', 'bg')]
    # A literal ending in a dot is a dynamic key prefix, not a complete catalog key.
    keys = set(re.findall(r'"((?:ui|common)\.[a-z0-9_.]*[a-z0-9_])"', source))
    for key in keys:
        for catalog in catalogs:
            if key not in catalog or not catalog[key]:
                raise AssertionError('Missing UI translation: ' + key)
    # Only append translations for keys actually referenced by this source.
    return source + '\n' + '\n'.join(catalogs[0][key] for key in sorted(keys))
