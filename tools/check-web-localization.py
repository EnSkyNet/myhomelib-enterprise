#!/usr/bin/env python3
from pathlib import Path
import re, sys
ROOT=Path(__file__).resolve().parents[1]
langs=['uk','en','bg']
base=ROOT/'myhomelib-web/src/main/resources/web-lang'

def props(path):
    out={}
    for no,line in enumerate(path.read_text(encoding='utf-8').splitlines(),1):
        line=line.strip()
        if not line or line.startswith('#'): continue
        if '=' not in line:
            raise AssertionError(f'{path}:{no}: expected key=value')
        k,v=line.split('=',1)
        k=k.strip()
        if not k.startswith('web.'):
            raise AssertionError(f'{path}:{no}: invalid web localization key {k!r}')
        if k in out: raise AssertionError(f'{path}:{no}: duplicate key {k}')
        if not v.strip(): raise AssertionError(f'{path}:{no}: empty value for {k}')
        out[k]=v
    return out

cats={lang:props(base/f'{lang}.properties') for lang in langs}
keys=set(cats['uk'])
for lang in langs[1:]:
    if set(cats[lang]) != keys:
        missing=sorted(keys-set(cats[lang])); extra=sorted(set(cats[lang])-keys)
        raise AssertionError(f'{lang}: key mismatch missing={missing} extra={extra}')

sources=[
 ROOT/'myhomelib-web/src/main/java/com/myhomelibcorp/web/WebLibraryRenderer.java',
 ROOT/'myhomelib-web/src/main/java/com/myhomelibcorp/web/WebReaderRenderer.java',
 ROOT/'myhomelib-opds/src/main/java/com/myhomelibcorp/opds/JdkOpdsServer.java',
 ROOT/'myhomelib-opds/src/main/java/com/myhomelibcorp/opds/Opds2JsonRenderer.java',
 ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/webreader/WebReaderService.java',
]
used=set()
for path in sources:
    text=path.read_text(encoding='utf-8')
    used.update(re.findall(r'"(web\.[a-z0-9_.]+)"', text))
    # Rendering/server classes must not carry Cyrillic UI literals. Comments are ignored line-wise.
    if 'WebReaderService.java' not in path.name:
        for no,line in enumerate(text.splitlines(),1):
            code=line.split('//',1)[0]
            if re.search(r'"[^"\n]*[А-Яа-яІіЇїЄєҐґ][^"\n]*"', code):
                raise AssertionError(f'{path}:{no}: hardcoded Cyrillic web UI literal: {line.strip()}')

missing=sorted(used-keys)
if missing: raise AssertionError(f'web localization keys used by code but absent from properties: {missing}')
# Raw protocol/UI text is forbidden in direct response sinks.
jdk=(ROOT/'myhomelib-opds/src/main/java/com/myhomelibcorp/opds/JdkOpdsServer.java').read_text(encoding='utf-8')
for no, line in enumerate(jdk.splitlines(), 1):
    if re.search(r'sendText(?:Safe)?\(\s*[^,]+,\s*[^,]+,\s*"', line):
        raise AssertionError(f'JdkOpdsServer.java:{no}: raw user-facing sendText literal: {line.strip()}')

print(f'PASS: web localization catalogues are synchronized ({len(keys)} keys); {len(used)} keys are referenced by web/OPDS code; no hardcoded Cyrillic renderer literals or raw sendText strings found.')
