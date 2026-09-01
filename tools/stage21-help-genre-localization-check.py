#!/usr/bin/env python3
from __future__ import annotations
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors=[]

def need(cond,msg):
    if not cond: errors.append(msg)

def text(path): return (ROOT/path).read_text(encoding='utf-8')

registry=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/HelpTopicRegistry.java')
help_service=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/HelpService.java')
lang_service=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/LanguageCatalogService.java')
loc_service=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/LocalizationService.java')
settings=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java')
nav=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/NavigationPanelController.java')
details=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/details/BookDetailsController.java')
workspace=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/navigation/WorkspaceManager.java')

need('HelpTopicRegistry' in workspace, 'WorkspaceManager must use central HelpTopicRegistry')
for topic in ['navigation','updates','filters','details','maintenance','actions','opds','backup']:
    need(f'"{topic}"' in registry, f'missing help topic {topic}')
need('new String[]{".md", ".txt", ".html"}' in help_service, 'HelpService must prefer Markdown with legacy fallbacks')
need('CURRENT_SCHEMA_VERSION = 3' in lang_service, 'language schema v3 missing')
need('DIAGNOSTICS_FILE = "language-diagnostics.txt"' in lang_service, 'language diagnostics file missing')
need('unsupported schemaVersion' in lang_service, 'future language schema must be rejected safely')
need('is legacy; supported with fallback' in lang_service, 'legacy schema fallback warning missing')
need('genreName(String genreCode, String fallback)' in loc_service, 'LocalizationService genre API missing')
need('localizationService.genreName(node.id(), node.label())' in nav, 'navigation genres are not localized by stable code')
need('localizationService.genreName(genre.getCode()' in details, 'details genres are not localized by stable code')
need('Діагностика мов...' in settings and 'showLanguageDiagnostics()' in settings, 'Settings language diagnostics UI missing')

md=list((ROOT/'myhomelib-ui/src/main/resources/help').rglob('*.md'))
need(len(md) >= 63, f'expected at least 63 Markdown help pages, got {len(md)}')
for langprefix in ['', 'en/', 'bg/']:
    for topic in ['index','navigation','updates','filters','details','maintenance','actions','opds','backup']:
        p=ROOT/'myhomelib-ui/src/main/resources/help'/langprefix/f'{topic}.md'
        need(p.is_file(), f'missing help page {p.relative_to(ROOT)}')

catalogs={}
for code in ['uk','en','bg']:
    rootp=ROOT/'Lang'/f'{code}.json'
    bundled=ROOT/'myhomelib-ui/src/main/resources/lang/default'/f'{code}.json'
    need(rootp.is_file(), f'missing Lang/{code}.json')
    need(bundled.is_file(), f'missing bundled {code}.json')
    if rootp.is_file() and bundled.is_file():
        need(rootp.read_bytes()==bundled.read_bytes(), f'{code} root/bundled catalog mismatch')
        data=json.loads(rootp.read_text(encoding='utf-8'))
        catalogs[code]=data
        need(data.get('schemaVersion')==3, f'{code} must use schemaVersion 3')
        need(isinstance(data.get('genres'),dict) and len(data['genres'])==335, f'{code} must contain all 335 extended canonical FB2 genres')
        need(isinstance(data.get('genreAliases'),dict) and len(data['genreAliases'])==272, f'{code} must contain all 272 legacy numeric FB2 aliases')
        need(isinstance(data.get('genreGroups'),dict) and len(data['genreGroups'])==23, f'{code} must contain 23 localized genre parent groups')
        need(isinstance(data.get('genreParents'),dict) and len(data['genreParents'])==335, f'{code} must map all 335 extended genres to semantic parents')
        need(isinstance(data.get('legacyBaseAliases'),dict) and len(data['legacyBaseAliases'])==25, f'{code} must contain 25 legacy base-category aliases')

if len(catalogs)==3:
    sets=[set(catalogs[c]['genres']) for c in ['uk','en','bg']]
    need(sets[0]==sets[1]==sets[2], 'shipped languages must expose same stable genre codes')
    aliases=[catalogs[c]['genreAliases'] for c in ['uk','en','bg']]
    need(aliases[0]==aliases[1]==aliases[2], 'shipped languages must expose identical numeric genre aliases')
    parents=[catalogs[c]['genreParents'] for c in ['uk','en','bg']]
    need(parents[0]==parents[1]==parents[2], 'shipped languages must expose identical semantic genre-parent mapping')
    base_aliases=[catalogs[c]['legacyBaseAliases'] for c in ['uk','en','bg']]
    need(base_aliases[0]==base_aliases[1]==base_aliases[2], 'shipped languages must expose identical legacy base-category aliases')

if errors:
    print('Stage 21 check FAILED:')
    for e in errors: print(' -',e)
    sys.exit(1)
print(f'Stage 21 check PASS: {len(md)} Markdown help pages; ' + ', '.join(f"{c}={len(catalogs[c]['genres'])} genres" for c in catalogs))
