#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
java = [p for m in ROOT.glob('myhomelib-*') for p in m.rglob('src/main/java/**/*.java')]
errors=[]

factory_owner = ROOT/'myhomelib-shared/src/main/java/com/myhomelibcorp/shared/xml/SecureXmlInputFactory.java'
for p in java:
    text=p.read_text(encoding='utf-8')
    if p != factory_owner and re.search(r'XMLInputFactory\.(?:newFactory|newInstance)\s*\(', text):
        errors.append(f'{p.relative_to(ROOT)} creates XMLInputFactory outside SecureXmlInputFactory')
    if re.search(r'SUPPORT_DTD\s*,\s*true|IS_SUPPORTING_EXTERNAL_ENTITIES\s*,\s*true', text):
        errors.append(f'{p.relative_to(ROOT)} enables unsafe XML DTD/external entities')

owner = factory_owner.read_text(encoding='utf-8') if factory_owner.exists() else ''
for token in ['XMLInputFactory.SUPPORT_DTD', 'XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES',
              'StAX provider does not support required secure property']:
    if token not in owner:
        errors.append(f'SecureXmlInputFactory missing fail-closed contract: {token}')

required_archive = {
    'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importer/zip/ZipImporter.java': ['ArchiveSafetyLimits', 'ZipCharsetSupport'],
    'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/cover/ZipArchiveReader.java': ['ArchiveSafetyLimits', 'ZipCharsetSupport'],
    'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxReader.java': ['ArchiveSafetyLimits', 'ZipCharsetSupport', 'LimitedInputStream'],
    'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importer/epub/EpubImporter.java': ['ArchiveSafetyLimits', 'LimitedInputStream'],
    'myhomelib-reader/src/main/java/com/myhomelibcorp/reader/format/epub/EpubParser.java': ['ArchiveSafetyLimits'],
}
for rel,tokens in required_archive.items():
    p=ROOT/rel
    text=p.read_text(encoding='utf-8') if p.exists() else ''
    for token in tokens:
        if token not in text:
            errors.append(f'{rel} missing archive safety primitive {token}')

inpx=(ROOT/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/importengine/InpxReader.java').read_text(encoding='utf-8')
for bad in [r'Path\.of\s*\(\s*[^)]*getName\s*\(', r'catch\s*\(\s*Exception[^)]*\)[^{]*\{[^}]*using default INPX structure']:
    if re.search(bad,inpx,re.S): errors.append('InpxReader regressed to unsafe archive-name/error fallback')

if errors:
    print('XML / ARCHIVE SECURITY CHECK: FAIL')
    for e in errors: print(' -',e)
    sys.exit(1)
print('XML / ARCHIVE SECURITY CHECK: PASS')
print(' - StAX creation is centralized and fail-closed')
print(' - DTD/external entities remain disabled')
print(' - ZIP/EPUB/INPX paths retain shared safety limits and charset handling')
