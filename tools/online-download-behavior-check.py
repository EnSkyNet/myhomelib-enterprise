#!/usr/bin/env python3
from pathlib import Path
import subprocess, tempfile, textwrap, sys
ROOT=Path(__file__).resolve().parents[1]
errors=[]
def need(c,m):
    if not c: errors.append(m)
def t(p): return (ROOT/p).read_text(encoding='utf-8')

book=t('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpOnlineBookDownloadAdapter.java')
cat=t('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpRemoteCatalogDownloadAdapter.java')
script=t('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/ConnectionScriptExecutor.java')
throttle=t('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/OnlineProgressThrottle.java')

for name,src in [('book',book),('catalog',cat),('ConnectionScript',script)]:
    need('OnlineProgressThrottle' in src, f'{name} path must use shared progress throttle')
need('NonRetryableRequestException' in script, 'ConnectionScript permanent HTTP statuses need a non-retryable path')
need('OnlineRetryPolicy.isRetryableStatus(status)' in script, 'ConnectionScript retry classification missing')
need('OnlineRetryPolicy.isRetryableStatus(status)' in cat[cat.index('private String fetchVersion'):], 'version marker retry classification missing')
need('response.headers().firstValue("Retry-After")' in cat[cat.index('private String fetchVersion'):], 'version marker Retry-After support missing')
need('MIN_BYTES = 1024L * 1024L' in throttle and 'MIN_NANOS = 100_000_000L' in throttle, 'shared throttle bounds changed unexpectedly')

if not errors:
    with tempfile.TemporaryDirectory() as td:
        td=Path(td)
        pkg=td/'com/myhomelibcorp/infrastructure/download'; pkg.mkdir(parents=True)
        (pkg/'OnlineProgressThrottle.java').write_text(throttle, encoding='utf-8')
        (td/'Smoke.java').write_text(textwrap.dedent('''
            import com.myhomelibcorp.infrastructure.download.OnlineProgressThrottle;
            public class Smoke {
              public static void main(String[] args) {
                OnlineProgressThrottle t = new OnlineProgressThrottle(0L);
                if (t.shouldEmit(512L * 1024L, 10L * 1024L * 1024L)) throw new AssertionError("sub-MiB callback was not throttled");
                if (!t.shouldEmit(1024L * 1024L, 10L * 1024L * 1024L)) throw new AssertionError("MiB threshold did not emit");
                if (!t.shouldEmit(10L * 1024L * 1024L, 10L * 1024L * 1024L)) throw new AssertionError("completion must emit");
              }
            }
        '''), encoding='utf-8')
        try:
            subprocess.run(['javac','-encoding','UTF-8','-d',str(td),str(pkg/'OnlineProgressThrottle.java'),str(td/'Smoke.java')],check=True)
            subprocess.run(['java','-cp',str(td),'Smoke'],check=True)
        except Exception as e:
            errors.append(f'OnlineProgressThrottle Java smoke failed: {e}')

if errors:
    print('ONLINE DOWNLOAD BEHAVIOR CHECK: FAILED')
    for e in errors: print(' -',e)
    sys.exit(1)
print('ONLINE DOWNLOAD BEHAVIOR CHECK: PASS')
print(' - shared progress callback throttle covers book/catalog/ConnectionScript: PASS')
print(' - permanent ConnectionScript GET statuses do not enter network retry catch: PASS')
print(' - version-marker retry classification + Retry-After: PASS')
print(' - actual Java throttle threshold smoke: PASS')
