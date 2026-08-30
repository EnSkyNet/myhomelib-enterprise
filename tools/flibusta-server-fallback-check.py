#!/usr/bin/env python3
from pathlib import Path
root=Path(__file__).resolve().parents[1]
adapter=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpRemoteCatalogDownloadAdapter.java').read_text(encoding='utf-8')
profile=(root/'myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/CatalogSourceProfile.java').read_text(encoding='utf-8')
checks={
 'canonical INPX root': 'https://alex80.github.io/mhl/download/inpx/' in profile,
 'canonical update root': 'https://alex80.github.io/mhl/update/' in profile,
 'markers optional': 'FLIBUSTA.fullVersionEndpoint()), flag, false)' in adapter and 'FLIBUSTA.incrementalVersionEndpoint()), flag, false)' in adapter,
 'markerless baseline fallback': 'fullVersion == null && extraVersion == null' in adapter and 'mhl.inpxBase() + FLIBUSTA.baselineFile()' in adapter,
 'embedded baseline version comparison': 'baselineNumber > current' in adapter,
 'unknown embedded version is not fake up-to-date': 'baselineNumber <= 0' in adapter,
}
failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(('PASS' if v else 'FAIL')+': '+k)
if failed: raise SystemExit(1)
print('Flibusta server fallback guard: PASS')
