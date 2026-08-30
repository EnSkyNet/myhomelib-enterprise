#!/usr/bin/env python3
from pathlib import Path
import subprocess, tempfile, textwrap

root=Path(__file__).resolve().parents[1]
lim_path=root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/OnlineRequestLimiter.java'
settings_path=root/'myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/settings/ApplicationSettingsPort.java'
lim=lim_path.read_text(encoding='utf-8')
book=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpOnlineBookDownloadAdapter.java').read_text(encoding='utf-8')
cat=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/HttpRemoteCatalogDownloadAdapter.java').read_text(encoding='utf-8')
ui=(root/'myhomelib-ui/src/main/java/com/myhomelibcorp/ui/service/ApplicationSettingsDialog.java').read_text(encoding='utf-8')
scenario=(root/'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/ConnectionScriptExecutor.java').read_text(encoding='utf-8')
assert 'online.maxParallelDownloads' in lim and 'online.maxParallelDownloadsPerHost' in lim
assert 'tryAcquire(ACQUIRE_POLL_MILLIS' in lim and 'cancel.get()' in lim
assert 'releaseHostReference' in lim and 'remaining == 0 ? null' in lim, 'historical host gates must be removed'
assert '@Component' in lim
assert 'OnlineRequestLimiter requestLimiter' in book and 'requestLimiter.acquire(uri, cancel)' in book
assert 'OnlineRequestLimiter requestLimiter' in cat and cat.count('requestLimiter.acquire(uri, cancel)') >= 2
assert 'online.maxParallelDownloadsPerHost' in ui
assert 'requestLimiter.acquire(uri, cancel)' in scenario, 'ConnectionScript must share HTTP limiter'
assert 'OnlineRetryPolicy.isRetryableStatus(status)' in scenario and 'OnlineRetryPolicy.delayMillis' in scenario, 'ConnectionScript must share retry policy'

# Compile the actual limiter with a tiny Spring annotation stub and exercise cancellation/concurrency.
with tempfile.TemporaryDirectory() as td:
    td=Path(td)
    lp=td/'com/myhomelibcorp/infrastructure/download'; lp.mkdir(parents=True)
    sp=td/'com/myhomelibcorp/application/port/out/settings'; sp.mkdir(parents=True)
    ap=td/'org/springframework/stereotype'; ap.mkdir(parents=True)
    (lp/'OnlineRequestLimiter.java').write_text(lim, encoding='utf-8')
    (sp/'ApplicationSettingsPort.java').write_text(settings_path.read_text(encoding='utf-8'), encoding='utf-8')
    (ap/'Component.java').write_text('package org.springframework.stereotype; public @interface Component {}', encoding='utf-8')
    (td/'Smoke.java').write_text(textwrap.dedent('''
        import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
        import com.myhomelibcorp.infrastructure.download.OnlineRequestLimiter;
        import java.net.URI;
        import java.util.Map;
        import java.util.concurrent.atomic.AtomicBoolean;
        import java.util.concurrent.atomic.AtomicReference;
        public class Smoke {
          static final class S implements ApplicationSettingsPort {
            public String get(String k, String d) {
              if (k.equals("online.maxParallelDownloads")) return "2";
              if (k.equals("online.maxParallelDownloadsPerHost")) return "1";
              return d;
            }
            public void put(String k,String v){} public void remove(String k){}
            public Map<String,String> findByPrefix(String p){return Map.of();}
          }
          public static void main(String[] args) throws Exception {
            OnlineRequestLimiter l=new OnlineRequestLimiter(new S());
            var p1=l.acquire(URI.create("https://a.test/1"), new AtomicBoolean(false));
            var p2=l.acquire(URI.create("https://b.test/1"), new AtomicBoolean(false));
            AtomicBoolean cancel=new AtomicBoolean(false); AtomicReference<Throwable> result=new AtomicReference<>();
            Thread t=new Thread(() -> { try { l.acquire(URI.create("https://a.test/2"), cancel).close(); result.set(new AssertionError("unexpected acquire")); } catch(Throwable e){ result.set(e); } });
            t.start(); Thread.sleep(150); cancel.set(true); t.join(1000);
            if (t.isAlive()) throw new AssertionError("cancelled waiter did not stop promptly");
            if (!(result.get() instanceof java.io.IOException)) throw new AssertionError("expected cancellation IOException: "+result.get());
            p2.close(); p1.close();
            var f=OnlineRequestLimiter.class.getDeclaredField("byHost"); f.setAccessible(true);
            if (!((java.util.Map<?,?>)f.get(l)).isEmpty()) throw new AssertionError("host gates leaked");
          }
        }
    '''), encoding='utf-8')
    java_files=[str(p) for p in td.rglob('*.java')]
    subprocess.run(['javac','-encoding','UTF-8','-d',str(td),*java_files],check=True)
    subprocess.run(['java','-cp',str(td),'Smoke'],check=True)

print('ONLINE CONCURRENCY CHECK: PASS')
print(' - shared global + per-host limiter covers book and catalog HTTP paths')
print(' - cancellation wait <= polling interval behavior exercised with actual Java class')
print(' - inactive host gates are released instead of accumulating historically')
print(' - ConnectionScript uses the same limiter and retry policy')
print(' - UI exposes per-host setting')
