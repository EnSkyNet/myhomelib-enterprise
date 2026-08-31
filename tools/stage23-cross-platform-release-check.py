#!/usr/bin/env python3
from __future__ import annotations
import subprocess, sys
from pathlib import Path
import yaml

ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(c,m):
    if not c: errors.append(m)
def txt(p): return (ROOT/p).read_text(encoding='utf-8')

workflow_path=ROOT/'.github/workflows/ci-release.yml'
need(workflow_path.is_file(),'GitHub Actions release workflow missing')
if workflow_path.is_file():
    workflow=txt('.github/workflows/ci-release.yml')
    try: yaml.safe_load(workflow)
    except Exception as e: errors.append(f'workflow YAML invalid: {e}')
    for platform in ['ubuntu-latest','macos-latest','windows-latest']:
        need(platform in workflow,f'CI matrix missing {platform}')
    need('clean verify -Pproduction' in workflow,'matrix must run full Maven verify')
    need('package-portable.sh' in workflow and 'package-portable.ps1' in workflow,'portable package steps missing')
    need('smoke-desktop.sh' in workflow and 'smoke-desktop.ps1' in workflow,'packaged-launcher smoke steps missing')
    need("tags: [ 'v*' ]" in workflow,'tag release trigger missing')
    need('SHA256SUMS' in workflow and 'gh release' in workflow,'tag checksums/publish path missing')
    need('needs: verify-package' in workflow,'publish job must depend on all matrix verification')

for script in ['package-desktop.sh','package-portable.sh','smoke-desktop.sh','release.sh','checksums.sh']:
    p=ROOT/script
    need(p.is_file(),f'{script} missing')
    if p.is_file():
        r=subprocess.run(['bash','-n',str(p)],capture_output=True,text=True)
        need(r.returncode==0,f'{script} bash syntax: {r.stderr.strip()}')

for script in ['package-desktop.ps1','package-portable.ps1','smoke-desktop.ps1','release.ps1','checksums.ps1']:
    need((ROOT/script).is_file(),f'{script} missing')

bootstrap=txt('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/MyHomeLibApp.java')
smoke=txt('myhomelib-bootstrap/src/main/java/com/myhomelibcorp/ReleaseSmokeCheck.java')
need('--release-smoke' in bootstrap and 'ReleaseSmokeCheck.run()' in bootstrap,'headless release smoke entry missing')
need('MYHOMELIB_RELEASE_SMOKE_OK' in smoke,'release smoke success marker missing')
for resource in ['view/MainView.fxml','db/migration/V1__init.sql','help/backup.md','lang/default/uk.json']:
    need(resource in smoke,f'release smoke does not verify {resource}')
need('launch(args)' in bootstrap and bootstrap.index('--release-smoke') < bootstrap.index('launch(args)'), 'release smoke must run before JavaFX launch')

portable_sh=txt('package-portable.sh'); portable_ps=txt('package-portable.ps1')
need('jpackage' in txt('package-desktop.sh') and '--type "$TYPE"' in txt('package-desktop.sh'),'Unix jpackage app-image path missing')
need('app-image' in portable_sh and '.tar.gz' in portable_sh,'Unix portable archive missing')
need('app-image' in portable_ps and 'Compress-Archive' in portable_ps,'Windows portable archive missing')
need('MHL_SKIP_BUILD' in txt('package-desktop.sh') and 'MHL_SKIP_BUILD' in txt('package-desktop.ps1'),'verified-build reuse switch missing')
need('--release-smoke' in txt('smoke-desktop.sh') and '--release-smoke' in txt('smoke-desktop.ps1'),'packaged launcher smoke argument missing')

release_doc=txt('MYHOMELIB-RELEASE.md')
need('does not download Maven dependencies at runtime' in release_doc,'no-network runtime contract missing from release docs')
need('real desktop smoke' in release_doc.lower(),'real desktop smoke requirement missing')
need('SHA256' in release_doc,'checksum documentation missing')

# The headless checker is deliberately pure JDK; prove it compiles without Maven/dependencies.
try:
    out=ROOT/'.stage23-smoke-classes'
    if out.exists():
        import shutil; shutil.rmtree(out)
    out.mkdir()
    r=subprocess.run(['javac','-d',str(out),str(ROOT/'myhomelib-bootstrap/src/main/java/com/myhomelibcorp/ReleaseSmokeCheck.java')],capture_output=True,text=True)
    need(r.returncode==0,'ReleaseSmokeCheck standalone javac failed: '+r.stderr.strip())
    import shutil; shutil.rmtree(out,ignore_errors=True)
except FileNotFoundError:
    errors.append('javac unavailable for ReleaseSmokeCheck compile smoke')

if errors:
    print('STAGE 23 CROSS-PLATFORM RELEASE CHECK: FAILED')
    for e in errors: print(' -',e)
    sys.exit(1)
print('STAGE 23 CROSS-PLATFORM RELEASE CHECK: PASS')
print(' - Windows/Linux/macOS Maven verify matrix + tag gating: PASS')
print(' - jpackage app-image portable archives + checksums: PASS')
print(' - packaged native launcher --release-smoke contract: PASS')
print(' - runtime dependency-download-free packaging contract: PASS')
print(' - ReleaseSmokeCheck standalone javac: PASS')
