#!/usr/bin/env python3
"""Compile/run critical v7.1 dependency-free code directly with the installed JDK.

This is a supplemental release gate for environments where Maven dependencies are
unavailable. It does not replace the Maven/JUnit suite.
"""
from __future__ import annotations

import shutil
import subprocess
import re
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def main() -> int:
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        raise SystemExit("JDK java/javac is required")

    with tempfile.TemporaryDirectory(prefix="mhl-v71-jdk-smoke-") as td:
        t = Path(td)
        src = t / "src"
        out = t / "classes"
        out.mkdir()

        # Minimal annotation stubs let us compile selected Spring-annotated infrastructure
        # code without pretending that the complete Spring runtime is present.
        write(src / "org/springframework/stereotype/Component.java", """
package org.springframework.stereotype;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
public @interface Component {}
""")
        write(src / "org/springframework/beans/factory/annotation/Autowired.java", """
package org.springframework.beans.factory.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) @Target({ElementType.CONSTRUCTOR,ElementType.FIELD,ElementType.METHOD})
public @interface Autowired {}
""")
        write(src / "com/myhomelibcorp/shared/smoke/SmokeLog.java", """
package com.myhomelibcorp.shared.smoke;
public final class SmokeLog {
 public void debug(String message, Object... args){} public void info(String message, Object... args){}
 public void warn(String message, Object... args){} public void error(String message, Object... args){}
}
""")

        # MacroResolver only needs a read-only subset of the real DTO/domain surface.
        write(src / "com/myhomelibcorp/application/dto/AuthorDto.java", """
package com.myhomelibcorp.application.dto;
public class AuthorDto {
 private String fullName="Author", lastName="Author";
 public String getFullName(){return fullName;} public String getLastName(){return lastName;}
}
""")
        write(src / "com/myhomelibcorp/application/dto/BookDto.java", """
package com.myhomelibcorp.application.dto;
import java.time.LocalDateTime;
import java.util.List;
public class BookDto {
 public String id="id-1", title="Title", authorsText="Author", series="Series", genresText="genre";
 public String language="uk", fileName="book.fb2", folder="folder", archiveEntry="book.fb2";
 public long fileSize=123; public int rate=4, progress=20, libraryRate=5; public Integer sequenceNumber=1, year=2026;
 public String libId="LIB-1", keywords="k", annotation="a", review="r", translators="t", publisher="p", city="c", isbn="9780000000000", sourceUrl="https://source";
 public LocalDateTime updateDate=LocalDateTime.of(2026,8,28,12,0);
 public List<AuthorDto> authors=List.of();
 public String getId(){return id;} public String getTitle(){return title;} public String getAuthorsText(){return authorsText;}
 public List<AuthorDto> getAuthors(){return authors;} public String getSeries(){return series;} public String getGenresText(){return genresText;} public String getLanguage(){return language;}
 public String getFileName(){return fileName;} public String getFolder(){return folder;} public String getArchiveEntry(){return archiveEntry;}
 public long getFileSize(){return fileSize;} public int getRate(){return rate;} public int getProgress(){return progress;}
 public int getLibraryRate(){return libraryRate;} public Integer getSequenceNumber(){return sequenceNumber;} public Integer getYear(){return year;}
 public String getLibId(){return libId;} public String getKeywords(){return keywords;} public String getAnnotation(){return annotation;}
 public String getReview(){return review;} public String getTranslators(){return translators;} public String getPublisher(){return publisher;}
 public String getCity(){return city;} public String getIsbn(){return isbn;} public String getSourceUrl(){return sourceUrl;}
 public LocalDateTime getUpdateDate(){return updateDate;}
}
""")
        write(src / "com/myhomelibcorp/domain/model/collection/Collection.java", """
package com.myhomelibcorp.domain.model.collection;
public class Collection {
 private final String name,url,user;
 public Collection(String name,String url,String user){this.name=name;this.url=url;this.user=user;}
 public String getName(){return name;} public String getUrl(){return url;} public String getUser(){return user;}
 public String getDecryptedPassword(){return "";}
}
""")
        write(src / "com/myhomelibcorp/shared/util/EncryptionUtil.java", """
package com.myhomelibcorp.shared.util;
public final class EncryptionUtil {
 private EncryptionUtil(){}
 public static boolean isEncrypted(String value){return value != null && value.startsWith("enc:");}
 public static String decrypt(String value){return value != null && value.startsWith("enc:") ? value.substring(4) : value;}
}
""")

        harness = write(src / "V71StandaloneSmoke.java", r'''
import com.myhomelibcorp.application.catalog.collectioninfo.*;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.download.DownloadPayloadValidator;
import com.myhomelibcorp.infrastructure.download.scenario.*;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class V71StandaloneSmoke {
  public static void main(String[] args) throws Exception {
    collectionInfoRoundTrip();
    scenarioParser();
    macroOnePass();
    sanitizer();
    networkPolicyRuntime();
    connectionScriptRuntime();
    archiveIntegrity();
    System.out.println("V7.1 STANDALONE JAVA SMOKE: PASS");
  }

  static void collectionInfoRoundTrip() {
    var p = new CollectionSourceProperties("Online", "catalog.inpx", 1, "notes", "https://host/catalog", "GET %URL%\nCHECK");
    var q = CollectionInfoCodec.parse(CollectionInfoCodec.serialize(p));
    check(p.name().equals(q.name()), "collection.info name");
    check(p.fileName().equals(q.fileName()), "collection.info file");
    check(p.type() == q.type(), "collection.info type");
    check(p.notes().equals(q.notes()), "collection.info notes");
    check(p.url().equals(q.url()), "collection.info URL");
    check(p.connectionScript().equals(q.connectionScript()), "collection.info multiline script");
  }

  static void scenarioParser() throws Exception {
    String script = "HTTPS://FLIBUSTA.IS/\nADD login user\nPOST https://host/login\nCHECK\nREDIR\nPAUSE 1\nGET %RESURL%";
    var commands = DownloadScenarioParser.parse(script);
    check(commands.size()==6, "legacy bare URL preamble ignored and all ConnectionScript commands parsed");
    check(commands.get(0).type()==DownloadScenarioCommand.Type.ADD, "ADD parsed");
    boolean rejected=false;
    try { DownloadScenarioParser.parse("SHELL rm"); } catch (DownloadScenarioException expected) { rejected=true; }
    check(rejected, "unknown command rejected");
  }

  static void macroOnePass() throws Exception {
    BookDto b = new BookDto();
    Collection c = new Collection("Online", "https://base.example", "reader");
    DownloadMacroResolver r = new DownloadMacroResolver(b,c,Path.of("/library"),"archive.zip","%URL%");
    String expanded = r.expand("https://x/?p=%PASS%&u=%URL%&r=%RESURL%", "https://redirect.example/book");
    check(expanded.contains("p=%URL%"), "macro replacement value is not rescanned");
    check(expanded.contains("u=https://base.example"), "%URL% expanded");
    check(expanded.contains("r=https://redirect.example/book"), "%RESURL% expanded");
  }

  static void sanitizer() throws Exception {
    String safe = SensitiveDataSanitizer.sanitizeUri(new URI("https://user:secret@example.test/book?token=abc123&x=ok"));
    check(!safe.contains("secret") && !safe.contains("abc123"), "URI secrets redacted");
    String text = SensitiveDataSanitizer.sanitizeText("Authorization: Bearer abcdef password=qwerty");
    check(!text.contains("abcdef") && !text.contains("qwerty"), "text secrets redacted");
  }

  static void networkPolicyRuntime() throws Exception {
    Path dir=Files.createTempDirectory("mhl-v71-net-policy-");
    HttpServer proxy=null;
    try {
      char[] secret="changeit-test".toCharArray();
      Path trust=dir.resolve("custom.p12");
      java.security.KeyStore ks=java.security.KeyStore.getInstance("PKCS12");
      ks.load(null,secret);
      try(OutputStream out=Files.newOutputStream(trust)){ ks.store(out,secret); }

      MapSettings tls=new MapSettings();
      tls.values.put("online.proxy.mode","DIRECT");
      tls.values.put("online.tls.trustStore",trust.toString());
      tls.values.put("online.tls.trustStoreType","PKCS12");
      tls.values.put("online.tls.trustStorePassword","enc:changeit-test");
      var tlsClient=new com.myhomelibcorp.infrastructure.download.OnlineHttpPolicy(tls).create(null);
      check(tlsClient.sslContext()!=null,"custom truststore loaded into SSLContext");

      MapSettings bad=new MapSettings();
      bad.values.put("online.proxy.mode","DIRECT");
      bad.values.put("online.tls.trustStore",trust.toString());
      bad.values.put("online.tls.trustStoreType","PKCS12");
      bad.values.put("online.tls.trustStorePassword","plaintext-secret");
      boolean rejected=false;
      try { new com.myhomelibcorp.infrastructure.download.OnlineHttpPolicy(bad).create(null); }
      catch(IllegalStateException expected){ rejected=true; }
      check(rejected,"plaintext truststore secret rejected");

      proxy=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
      proxy.createContext("/", exchange -> {
        try {
          byte[] body="proxied-ok".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
        } finally { exchange.close(); }
      });
      proxy.start();
      MapSettings ps=new MapSettings();
      ps.values.put("online.proxy.mode","HTTP");
      ps.values.put("online.proxy.host","127.0.0.1");
      ps.values.put("online.proxy.port",Integer.toString(proxy.getAddress().getPort()));
      var policy=new com.myhomelibcorp.infrastructure.download.OnlineHttpPolicy(ps);
      var response=policy.create(null).send(java.net.http.HttpRequest.newBuilder(new URI("http://example.invalid/probe"))
          .timeout(policy.requestTimeout()).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      check(response.statusCode()==200 && "proxied-ok".equals(response.body()),"HTTP proxy routing executed");
    } finally {
      if(proxy!=null) proxy.stop(0);
      try(var walk=Files.walk(dir)){ walk.sorted(Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(Exception ignored){}}); }
    }
  }

  static void connectionScriptRuntime() throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
    try {
      server.createContext("/login", exchange -> {
        try {
          String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
          if(!"POST".equals(exchange.getRequestMethod()) || !body.contains("name=\"login\"") || !body.contains("reader")) {
            exchange.sendResponseHeaders(400,-1); return;
          }
          exchange.getResponseHeaders().add("Set-Cookie","sid=ok; Path=/");
          exchange.getResponseHeaders().add("Location","/book");
          exchange.sendResponseHeaders(303,-1);
        } finally { exchange.close(); }
      });
      server.createContext("/book", exchange -> {
        try {
          String cookie=exchange.getRequestHeaders().getFirst("Cookie");
          if(cookie==null || !cookie.contains("sid=ok")) { exchange.sendResponseHeaders(403,-1); return; }
          byte[] data="<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type","application/fb2+xml");
          exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data);
        } finally { exchange.close(); }
      });
      server.createContext("/plain", exchange -> {
        try {
          byte[] data="<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data);
        } finally { exchange.close(); }
      });
      server.createContext("/unicode/книга", exchange -> {
        try {
          byte[] data="<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data);
        } finally { exchange.close(); }
      });
      server.start();
      int port=server.getAddress().getPort();
      Path dir=Files.createTempDirectory("mhl-v71-script-");
      try {
        BookDto b=new BookDto(); b.fileName="book.fb2"; b.archiveEntry=null;
        Collection c=new Collection("Online","http://127.0.0.1:"+port,"reader");
        MapSettings settings=new MapSettings(); settings.values.put("online.retryCount","0"); settings.values.put("online.proxy.mode","DIRECT");
        DownloadPayloadValidator validator=new DownloadPayloadValidator(new SimpleArchiveReader(),settings);
        ConnectionScriptExecutor executor=new ConnectionScriptExecutor(settings,validator);
        Path target=dir.resolve("book.fb2");

        var post=executor.execute("ADD login reader\nPOST http://127.0.0.1:"+port+"/login\nREDIR\nCHECK",
          b,c,dir,"",target,false,new AtomicBoolean(false),v->{});
        check(post.checked(),"POST/ADD/REDIR/CHECK executed");
        check(post.responseUri().getPath().equals("/book"),"redirect result retained");
        check(Files.size(post.payload())>0,"POST redirect payload written");

        var get=executor.execute("PAUSE 1\nGET http://127.0.0.1:"+port+"/plain\nCHECK",
          b,c,dir,"",target,false,new AtomicBoolean(false),v->{});
        check(get.checked(),"PAUSE/GET/CHECK executed");

        var unicode=executor.execute("GET http://127.0.0.1:"+port+"/unicode/книга\nCHECK",
          b,c,dir,"",target,false,new AtomicBoolean(false),v->{});
        check(unicode.checked(),"Unicode URL executed");

        boolean cancelled=false;
        try { executor.execute("PAUSE 1000\nGET http://127.0.0.1:"+port+"/plain",b,c,dir,"",target,false,new AtomicBoolean(true),v->{}); }
        catch(DownloadScenarioException expected){ cancelled=expected.getMessage().contains("скасовано"); }
        check(cancelled,"ConnectionScript cancellation propagated");
      } finally {
        try(var walk=Files.walk(dir)){ walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}}); }
      }
    } finally { server.stop(0); }
  }

  static void archiveIntegrity() throws Exception {
    Path dir = Files.createTempDirectory("mhl-v71-zip-");
    try {
      Path good=dir.resolve("good.zip");
      zip(good, new String[][]{{"book.fb2","<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>"}});
      BookDto b=new BookDto(); b.archiveEntry="book.fb2"; b.fileName="book.fb2";
      new DownloadPayloadValidator(new SimpleArchiveReader(), new MapSettings()).validate(good,good,b,true);

      String flibustaEntry="Romanovich_Zemli-chudovishch_1_Zemli-chudovishch.586491.fb2";
      Path renamed=dir.resolve("renamed.zip");
      zip(renamed,new String[][]{{flibustaEntry,"<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>"}});
      BookDto renamedBook=new BookDto(); renamedBook.archiveEntry="586491.fb2"; renamedBook.fileName="586491.fb2"; renamedBook.libId="586491";
      String resolved=new DownloadPayloadValidator(new SimpleArchiveReader(),new MapSettings()).validate(renamed,renamed,renamedBook,true);
      check(flibustaEntry.equals(resolved),"Flibusta renamed FB2 entry resolved and returned");

      Path dup=dir.resolve("dup.zip");
      zip(dup,new String[][]{{"book.fb2","<FictionBook/>"},{"BOOK.FB2","<FictionBook/>"}});
      boolean rejected=false;
      try { new DownloadPayloadValidator(new SimpleArchiveReader(),new MapSettings()).validate(dup,dup,b,true); }
      catch(IOException expected){ rejected=expected.getMessage().contains("дубльоване"); }
      check(rejected,"case-insensitive duplicate archive entry rejected");
    } finally {
      try(var walk=Files.walk(dir)){ walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}}); }
    }
  }

  static void zip(Path path,String[][] entries) throws Exception {
    try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(path))){
      for(String[] e:entries){ out.putNextEntry(new ZipEntry(e[0])); out.write(e[1].getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
    }
  }

  static class MapSettings implements ApplicationSettingsPort {
    final Map<String,String> values=new HashMap<>(Map.of("online.archive.highReliabilityValidation","true"));
    public String get(String k,String d){return values.getOrDefault(k,d);} public void put(String k,String v){values.put(k,v);}
    public void remove(String k){values.remove(k);} public Map<String,String> findByPrefix(String p){return Map.of();}
  }

  static class SimpleArchiveReader implements ArchiveReader {
    public boolean isArchive(Path p){return true;}
    public List<String> listEntries(Path p){
      try(ZipFile z=new ZipFile(p.toFile())){ return z.stream().filter(e->!e.isDirectory()).map(ZipEntry::getName).toList(); }
      catch(Exception e){ return List.of(); }
    }
    public Optional<InputStream> readEntry(Path p,String name){
      try {
        ZipFile z=new ZipFile(p.toFile()); ZipEntry e=z.getEntry(name);
        if(e==null){z.close();return Optional.empty();}
        InputStream d=z.getInputStream(e);
        return Optional.of(new FilterInputStream(d){public void close() throws IOException{super.close();z.close();}});
      } catch(Exception e){ return Optional.empty(); }
    }
    public Optional<InputStream> findFirstEntry(Path p,Predicate<String> f){
      for(String n:listEntries(p)) if(f.test(n)) return readEntry(p,n); return Optional.empty();
    }
  }

  static void check(boolean ok,String label){ if(!ok) throw new AssertionError(label); }
}
''')

        real_sources = [
            ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/collectioninfo/CollectionInfoCodec.java",
            ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/catalog/collectioninfo/CollectionSourceProperties.java",
            ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/cover/ArchiveReader.java",
            ROOT / "myhomelib-application/src/main/java/com/myhomelibcorp/application/port/out/settings/ApplicationSettingsPort.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/archive/ArchiveEntryNameSupport.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/DownloadPayloadValidator.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/OnlineHttpPolicy.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/OnlineRequestLimiter.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/OnlineProgressThrottle.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/OnlineRetryPolicy.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/ConnectionScriptExecutor.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/DownloadScenarioCommand.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/DownloadScenarioException.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/DownloadScenarioParser.java",
            ROOT / "myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/download/scenario/DownloadMacroResolver.java",
            ROOT / "myhomelib-shared/src/main/java/com/myhomelibcorp/shared/security/SensitiveDataSanitizer.java",
        ]
        # Lombok is intentionally not a dependency of this standalone gate. For sources using
        # @Slf4j, compile an equivalent temporary copy with a no-op logger field. This keeps the
        # smoke harness aligned with production method contracts without requiring Lombok/Maven.
        compile_sources = []
        transformed_root = t / "real-src"
        for real in real_sources:
            text = real.read_text(encoding="utf-8")
            if "@Slf4j" in text:
                text = text.replace("import lombok.extern.slf4j.Slf4j;\n", "").replace("@Slf4j\n", "")
                class_match = re.search(r"(public\s+(?:final\s+)?class\s+\w+[^\{]*\{)", text)
                if not class_match:
                    raise SystemExit(f"cannot inject standalone logger into {real}")
                injected = class_match.group(1) + "\n    private static final com.myhomelibcorp.shared.smoke.SmokeLog log = new com.myhomelibcorp.shared.smoke.SmokeLog();"
                text = text[:class_match.start()] + injected + text[class_match.end():]
                package = re.search(r"package\s+([\w.]+);", text).group(1)
                target = transformed_root / Path(*package.split('.')) / real.name
                write(target, text)
                compile_sources.append(target)
            else:
                compile_sources.append(real)
        generated = list(src.rglob("*.java"))
        cmd = [javac, "--add-modules", "jdk.httpserver", "-encoding", "UTF-8", "-d", str(out), *map(str, compile_sources), *map(str, generated)]
        cp = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True)
        if cp.returncode != 0:
            print(cp.stdout)
            print(cp.stderr)
            raise SystemExit("standalone javac failed")
        cp = subprocess.run([java, "--add-modules", "jdk.httpserver", "-cp", str(out), "V71StandaloneSmoke"], cwd=ROOT, text=True, capture_output=True)
        print(cp.stdout, end="")
        if cp.returncode != 0:
            print(cp.stderr)
            raise SystemExit("standalone Java smoke failed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
