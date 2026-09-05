#!/usr/bin/env python3
"""Offline Stage 17/18 OPDS regression and loopback HTTP smoke test."""
from pathlib import Path
import re, shutil, subprocess, tempfile, textwrap

ROOT = Path(__file__).resolve().parents[1]
errors=[]
def fail(x): errors.append(x)
def text(rel):
    p=ROOT/rel
    if not p.exists(): fail(f"missing {rel}"); return ""
    return p.read_text(encoding='utf-8')

rootpom=text('pom.xml'); bootpom=text('myhomelib-bootstrap/pom.xml'); opdspom=text('myhomelib-opds/pom.xml')
if '<module>myhomelib-opds</module>' not in rootpom: fail('root reactor missing myhomelib-opds')
if '<artifactId>myhomelib-opds</artifactId>' not in bootpom: fail('bootstrap does not compose OPDS module')
for forbidden in ('myhomelib-infrastructure','myhomelib-ui','javafx-controls','spring-jdbc','lucene-core'):
    if forbidden in opdspom: fail(f'OPDS POM must not depend on {forbidden}')

server=text('myhomelib-opds/src/main/java/com/myhomelibcorp/opds/JdkOpdsServer.java')
for marker in ('/health','/opds/authors','/opds/series','/opds/genres','/opds/search','/opds/books/','/opds/download/',
               'WWW-Authenticate','Basic realm','Files.copy(path, out)','Executors.newThreadPerTaskExecutor'):
    if marker not in server: fail(f'OPDS server contract missing: {marker}')
for forbidden in ('javafx.','org.springframework.jdbc','com.myhomelibcorp.infrastructure','readAllBytes('):
    if forbidden in server: fail(f'OPDS server forbidden dependency/behavior: {forbidden}')

adapter=text('myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/opds/SqliteOpdsCatalogQueryAdapter.java')
if adapter.count('LIMIT ? OFFSET ?') < 4: fail('OPDS SQLite lists are not consistently LIMIT/OFFSET bounded')
if 'findAll(' in adapter or 'streamAll(' in adapter: fail('OPDS adapter must not materialize full catalog')
if 'COUNT(DISTINCT LOWER(TRIM(series)))' not in adapter: fail('series count/group semantics must be case-insensitive')

ui=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/opds/OpdsUiService.java')
lifecycle=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/opds/OpdsDesktopLifecycle.java')
main=text('myhomelib-ui/src/main/java/com/myhomelibcorp/ui/controller/MainController.java')
fxml=text('myhomelib-ui/src/main/resources/view/MainView.fxml')
for marker in ('basicAuthEnabled','autostart','exposedBeyondLocalhost','127.0.0.1','firewall'):
    if marker not in ui and marker not in lifecycle: fail(f'Stage 18 UI/lifecycle missing {marker}')
if '@PostConstruct' not in lifecycle or '@PreDestroy' not in lifecycle: fail('OPDS desktop lifecycle missing start/stop hooks')
if 'CoreActions.OPDS_MANAGE' not in main or 'fx:id="opdsMenuItem"' not in fxml: fail('OPDS main-menu ActionRegistry wiring missing')
if 'com.myhomelibcorp.opds' in ui+lifecycle+main: fail('UI must depend on application OPDS control, not implementation')

arch=text('ARCHITECTURE.md')
if '12 modules' not in arch or 'opds            -> application' not in arch: fail('architecture documentation not updated for OPDS module')

required=[
 'myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/opds/OpdsCatalogQueryPort.java',
 'myhomelib-application/src/main/java/com/myhomelibcorp/application/opds/OpdsCatalogService.java',
 'myhomelib-application/src/main/java/com/myhomelibcorp/application/opds/OpdsDownloadService.java',
 'myhomelib-application/src/main/java/com/myhomelibcorp/application/opds/OpdsServerControl.java',
 'myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/opds/SqliteOpdsCatalogQueryAdapter.java',
 'myhomelib-opds/src/test/java/com/myhomelibcorp/opds/JdkOpdsServerTest.java']
for rel in required:
    if not (ROOT/rel).exists(): fail(f'missing {rel}')

# Compile/run a transformed copy of the real JDK HTTP server. We strip only Spring/Lombok/logging
# because Maven dependencies are intentionally unavailable in this validation container.
def http_smoke():
    with tempfile.TemporaryDirectory(prefix='mhl-opds-check-') as td:
        td=Path(td); src=td/'src'; classes=td/'classes'
        pkg=src/'com/myhomelibcorp/application/opds'; pkg.mkdir(parents=True)
        opkg=src/'com/myhomelibcorp/opds'; opkg.mkdir(parents=True)
        for name in ('OpdsFacetDto','OpdsBookDto','OpdsPage','OpdsBookQuery','OpdsServerSettings','OpdsServerStatus','OpdsServerControl'):
            shutil.copy(ROOT/f'myhomelib-application/src/main/java/com/myhomelibcorp/application/opds/{name}.java', pkg/f'{name}.java')
        (pkg/'OpdsCatalogService.java').write_text(textwrap.dedent('''
            package com.myhomelibcorp.application.opds;
            import java.util.*;
            public class OpdsCatalogService {
              public OpdsPage<OpdsFacetDto> authors(int o,int l) { var a=List.of(new OpdsFacetDto("a1","Автор Один",2),new OpdsFacetDto("a2","Автор Два",1)); int f=Math.min(o,a.size()), t=Math.min(f+l,a.size()); return new OpdsPage<>(a.subList(f,t),a.size(),o,l); }
              public OpdsPage<OpdsFacetDto> series(int o,int l){ return new OpdsPage<>(List.of(new OpdsFacetDto("Серія","Серія",1)),1,o,l); }
              public OpdsPage<OpdsFacetDto> genres(int o,int l){ return new OpdsPage<>(List.of(new OpdsFacetDto("sf","SF",1)),1,o,l); }
              public OpdsPage<OpdsBookDto> books(OpdsBookQuery q){ var a=List.of(new OpdsBookDto("b1","Book One","Author","Series","uk",2026,"Ann","fb2",true,"b.fb2",""),new OpdsBookDto("b2","Book Two","Author","Series","uk",2026,"Ann","epub",false,"b.epub","")); int f=Math.min(q.offset(),a.size()),t=Math.min(f+q.limit(),a.size()); return new OpdsPage<>(a.subList(f,t),a.size(),q.offset(),q.limit()); }
              public Optional<OpdsBookDto> book(String id){ return Optional.of(new OpdsBookDto(id,"Book","Author","","uk",2026,"","fb2",false,"book.fb2","")); }
            }
        '''),encoding='utf-8')
        (pkg/'OpdsDownloadService.java').write_text(textwrap.dedent('''
            package com.myhomelibcorp.application.opds;
            import java.nio.file.Path; import java.util.Optional;
            public class OpdsDownloadService {
              public Optional<Download> open(String id){ return Optional.empty(); }
              public record Content(Path path) {}
              public record Download(String fileName, Content content) implements AutoCloseable { public void close(){} }
            }
        '''),encoding='utf-8')
        transformed=server
        transformed=re.sub(r'import lombok[^;]+;\n','',transformed)
        transformed=re.sub(r'import org\.springframework[^;]+;\n','',transformed)
        transformed=re.sub(r'@Component\n|@RequiredArgsConstructor\n|@Slf4j\n','',transformed)
        transformed=re.sub(r'^\s*log\.(?:info|warn)\([^;]*;\n','',transformed,flags=re.M)
        transformed=re.sub(r'public class JdkOpdsServer implements (?:com\.myhomelibcorp\.application\.opds\.)?OpdsServerControl \{', '''public class JdkOpdsServer implements OpdsServerControl {\n    public JdkOpdsServer(OpdsCatalogService catalog, OpdsDownloadService downloads) { this.catalog = catalog; this.downloads = downloads; }''', transformed)
        (opkg/'JdkOpdsServer.java').write_text(transformed,encoding='utf-8')
        (opkg/'Smoke.java').write_text(textwrap.dedent('''
            package com.myhomelibcorp.opds;
            import com.myhomelibcorp.application.opds.*; import java.net.*; import java.net.http.*; import java.net.ServerSocket; import java.nio.charset.StandardCharsets; import java.util.Base64;
            public class Smoke {
              static int free() throws Exception { try(var s=new ServerSocket(0)){return s.getLocalPort();} }
              static HttpResponse<String> get(HttpClient c,String u,String auth) throws Exception { var b=HttpRequest.newBuilder(URI.create(u)).GET(); if(auth!=null)b.header("Authorization",auth); return c.send(b.build(),HttpResponse.BodyHandlers.ofString()); }
              static void check(boolean b,String m){ if(!b) throw new AssertionError(m); }
              public static void main(String[] a) throws Exception {
                var server=new JdkOpdsServer(new OpdsCatalogService(),new OpdsDownloadService()); var c=HttpClient.newHttpClient(); int p=free();
                var st=server.start(new OpdsServerSettings("127.0.0.1",p,false,"","",false)); check(st.running()&&!st.exposedBeyondLocalhost(),"loopback status");
                var root=get(c,"http://127.0.0.1:"+p+"/opds",null); check(root.statusCode()==200&&root.body().contains("/opds/authors"),"root");
                var au=get(c,"http://127.0.0.1:"+p+"/opds/authors?limit=1",null); check(au.statusCode()==200&&au.body().contains("Автор Один")&&!au.body().contains("Автор Два"),"bounded authors");
                var se=get(c,"http://127.0.0.1:"+p+"/opds/search?q=hello&limit=1",null); check(se.body().contains("q=hello&amp;offset=1"),"search pagination preserves query");
                check(get(c,"http://127.0.0.1:"+p+"/opds/download/nope",null).statusCode()==404,"missing download"); server.stop();
                p=free(); server.start(new OpdsServerSettings("127.0.0.1",p,true,"reader","secret",false));
                check(get(c,"http://127.0.0.1:"+p+"/health",null).statusCode()==200,"health public"); check(get(c,"http://127.0.0.1:"+p+"/opds",null).statusCode()==401,"auth denied");
                String tok="Basic "+Base64.getEncoder().encodeToString("reader:secret".getBytes(StandardCharsets.UTF_8)); check(get(c,"http://127.0.0.1:"+p+"/opds",tok).statusCode()==200,"auth accepted"); server.stop();
                System.out.println("OPDS HTTP SMOKE: PASS");
              }
            }
        '''),encoding='utf-8')
        java=[str(p) for p in src.rglob('*.java')]
        classes.mkdir()
        cp=subprocess.run(['javac','--release','21','-d',str(classes),*java],capture_output=True,text=True,timeout=30)
        if cp.returncode: raise RuntimeError('javac failed:\n'+cp.stdout+cp.stderr)
        run=subprocess.run(['java','-cp',str(classes),'com.myhomelibcorp.opds.Smoke'],capture_output=True,text=True,timeout=30)
        if run.returncode: raise RuntimeError('HTTP smoke failed:\n'+run.stdout+run.stderr)
        print(run.stdout.strip())

if errors:
    print('STAGE 17/18 OPDS CHECK: FAIL')
    for e in errors: print(' -',e)
    raise SystemExit(1)
try:
    http_smoke()
except Exception as e:
    print('STAGE 17/18 OPDS CHECK: FAIL')
    print(e)
    raise SystemExit(1)
print('STAGE 17/18 OPDS CHECK: PASS')
