#!/usr/bin/env python3
from __future__ import annotations
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OLD_SOURCE = ROOT / 'tools/reference/genres_fb2.txt'
UK_SOURCE = ROOT / 'tools/reference/genres_fb2_uk.glst'
errors=[]

def need(cond,msg):
    if not cond: errors.append(msg)

def parse_extended(path: Path):
    out=[]
    for raw in path.read_text(encoding='utf-8-sig').splitlines():
        line=raw.strip()
        if not line or line.startswith('#') or ';' not in line: continue
        left,name=line.split(';',1)
        parts=left.split()
        if len(parts)<2: continue
        out.append((parts[0],parts[-1],name.strip()))
    return out

old=parse_extended(OLD_SOURCE)
uk=parse_extended(UK_SOURCE)
old_codes=[code for _,code,_ in old]
uk_codes=[code for _,code,_ in uk]
canonical=list(uk_codes)
canonical.extend(code for code in old_codes if code not in set(uk_codes))
legacy_aliases={numeric:code for numeric,code,_ in old}

need(len(old)==272, f'legacy reference must contain 272 extended genres, got {len(old)}')
need(len(uk)==294, f'uk supplemental reference must contain 294 extended genres, got {len(uk)}')
need(len(canonical)==335, f'union must contain 335 extended genres, got {len(canonical)}')
need(len(set(canonical))==335, 'canonical extended genre codes must be unique')
need(len(set(uk_codes)-set(old_codes))==63, 'uk supplemental reference must add 63 stable codes')
need(len(legacy_aliases)==272, f'legacy dictionary must contain 272 numeric aliases, got {len(legacy_aliases)}')
need('0.3.0' not in legacy_aliases, 'supplemental glst numeric positions must not leak into the legacy alias namespace')

parent_maps=[]
base_alias_maps=[]
for lang in ('uk','en','bg'):
    path=ROOT/'Lang'/f'{lang}.json'
    data=json.loads(path.read_text(encoding='utf-8'))
    genres=data.get('genres') or {}
    genre_aliases=data.get('genreAliases') or {}
    genre_groups=data.get('genreGroups') or {}
    genre_parents=data.get('genreParents') or {}
    legacy_base_aliases=data.get('legacyBaseAliases') or {}
    need(data.get('schemaVersion')==3, f'{lang}: schemaVersion must be 3')
    need(list(genres.keys())==canonical, f'{lang}: genres must contain only the 335 extended canonical codes in stable order')
    need(not any(re.fullmatch(r'0\.\d+', key) for key in genres), f'{lang}: base 0.x categories must stay outside the extended display dictionary')
    need(genre_aliases==legacy_aliases, f'{lang}: legacy numeric aliases must remain compatible with original genres_fb2')
    need(len(genre_groups)==23, f'{lang}: must define 23 localized semantic parent groups')
    need(set(genre_parents)==set(canonical), f'{lang}: every canonical genre must have a semantic parent')
    need(set(genre_parents.values()) <= set(genre_groups), f'{lang}: every genre parent must target genreGroups')
    need(len(legacy_base_aliases)==25, f'{lang}: must define 25 legacy base-category aliases')
    need(set(legacy_base_aliases.values()) <= set(genre_groups), f'{lang}: legacy base aliases must target genreGroups')
    need(all(isinstance(v,str) and v.strip() for v in genres.values()), f'{lang}: every extended genre must have a human label')
    need(all(v.strip()!=k.strip() for k,v in genres.items()), f'{lang}: internal genre codes must never be display labels')
    need(all(isinstance(v,str) and v.strip() for v in genre_groups.values()), f'{lang}: every parent group must have a human label')
    meta=data.get('genreCatalog') or {}
    need(meta.get('displayPolicy')=='extended-with-parent-fallback', f'{lang}: parent fallback display policy is required')
    need(meta.get('entries')==335, f'{lang}: genreCatalog.entries must be 335')
    need(meta.get('parentGroups')==23 and meta.get('parentLinks')==335, f'{lang}: genreCatalog parent metadata mismatch')
    parent_maps.append(genre_parents)
    base_alias_maps.append(legacy_base_aliases)
    bundled=ROOT/'myhomelib-ui/src/main/resources/lang/default'/f'{lang}.json'
    need(path.read_bytes()==bundled.read_bytes(), f'{lang}: root and bundled language files differ')

need(parent_maps[0] == parent_maps[1] == parent_maps[2], 'semantic parent mapping must be language-independent')
need(base_alias_maps[0] == base_alias_maps[1] == base_alias_maps[2], 'legacy base alias mapping must be language-independent')
need(parent_maps[0].get('sf_history') == 'speculative', 'sf_history must belong to speculative parent group')
need(parent_maps[0].get('det_classic') == 'detective', 'det_classic must belong to detective parent group')

uk_data=json.loads((ROOT/'Lang/uk.json').read_text(encoding='utf-8'))
need(uk_data['genreAliases'].get('0.1.1')=='sf_history', 'legacy 0.1.1 must resolve to sf_history')
need(uk_data['genres'].get('sf_history')=='Альтернативна історія', 'sf_history Ukrainian display label mismatch')
need(uk_data['legacyBaseAliases'].get('0.1')=='speculative', 'legacy base 0.1 must resolve to speculative')
need(uk_data['genreGroups'].get('speculative')=='Фантастика', 'speculative Ukrainian parent label mismatch')
need(uk_data['legacyBaseAliases'].get('0.3')=='home', 'legacy base numbering must stay bound to original genres_fb2.txt')

service=(ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/service/GenreServiceImpl.java').read_text(encoding='utf-8')
need('ClassPathResource' not in service and 'loadGenresFromResource' not in service,
     'GenreServiceImpl must not load a bundled genres_fb2 runtime fallback')
need(not (ROOT/'myhomelib-infrastructure/src/main/resources/genres_fb2.txt').exists(),
     'genres_fb2.txt must not remain in runtime resources')
lang_service=(ROOT/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/LanguageCatalogService.java').read_text(encoding='utf-8')
need('legacyBaseAliases' in lang_service and 'genreParents' in lang_service and 'genreGroups' in lang_service,
     'LanguageCatalogService must resolve semantic parent fallback from language catalogs')
need('shouldDisplayGenre' in lang_service and 'hasExactGenre' in lang_service,
     'LanguageCatalogService must suppress a base fallback when a specific sibling exists')
need('looksLikeInternalGenreCode' in lang_service,
     'LanguageCatalogService must not expose an internal genre code as fallback text')

export=(ROOT/'myhomelib-application/src/main/java/com/myhomelibcorp/application/usecase/export/ExportToDeviceUseCase.java').read_text(encoding='utf-8')
need('.replace("%a", sanitizeFileName(firstAuthorName(book)))' in export,
     'export %a placeholder must use only the first author')

if errors:
    print('Genre/export authority check FAILED:')
    for e in errors: print(' -',e)
    sys.exit(1)
print('Genre/export authority check PASS: 335 extended genres, 23 semantic parents, legacy base fallback, first-author export')
