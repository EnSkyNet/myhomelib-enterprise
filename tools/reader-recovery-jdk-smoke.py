#!/usr/bin/env python3
"""Compile real Reader recovery classes and run cross-JVM regression checks.
No Maven, external dependencies, or access to the user's library required.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader"
SHARED = ROOT / "myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util"
READER = ROOT / "myhomelib-reader/src/main/java/com/myhomelibcorp/reader"
SMOKE_SOURCE = 'package com.myhomelibcorp.ui.reader;\n\nimport com.myhomelibcorp.reader.api.ReaderPosition;\nimport com.myhomelibcorp.reader.audio.AudioPosition;\nimport java.io.IOException;\nimport java.nio.channels.FileChannel;\nimport java.nio.channels.FileLock;\nimport java.nio.file.*;\nimport java.util.*;\nimport java.util.concurrent.*;\nimport java.util.concurrent.atomic.AtomicInteger;\n\npublic class RecoveryGuardSmoke {\n  private static int checks;\n  static void ok(boolean cond,String message) { checks++; if (!cond) throw new AssertionError(message); }\n  public static void main(String[] args) throws Exception {\n    if (args.length>0 && args[0].equals("hold")) {\n      Path p=Path.of(args[1]);\n      try(var c=FileChannel.open(p, StandardOpenOption.WRITE); var lock=c.lock()) {\n        System.out.println("LOCKED");System.out.flush();Thread.sleep(4300);\n      }\n      return;\n    }\n    Path dir=Files.createTempDirectory("mhl-v8-recovery-");\n    var text1=new ReaderPositionRecoveryJournal(dir.resolve("text"));\n    var text2=new ReaderPositionRecoveryJournal(dir.resolve("text"));\n    var a=new ReaderPosition(0,10,0,0);\n    var b=new ReaderPosition(0,20,0,0);\n    var first=text1.prepare("coll","book",null,a,100);\n    var second=text2.prepare("coll","book",null,b,100);\n    ok(!text1.acknowledgeIfMatches("coll","book",first),"stale text ack removed newer pending");\n    ok(text2.load("coll","book").orElseThrow().equals(second),"newer text lost");\n    ok(text2.acknowledgeIfMatches("coll","book",second),"text ack failed");\n    ok(text1.load("coll","book").isEmpty(),"text still pending");\n    var empty = text1.prepare("coll","empty", "", a, 100);\n    ok(text1.acknowledgeIfMatches("coll","empty",empty), "empty-base text acknowledge failed");\n    var audio1=new ReaderAudioRecoveryJournal(dir.resolve("audio"));\n    var audio2=new ReaderAudioRecoveryJournal(dir.resolve("audio"));\n    var older=audio1.prepare("coll","book",null,new AudioPosition(10,0,0),2);\n    var newer=audio2.prepare("coll","book",null,new AudioPosition(20,0,0),4);\n    ok(!audio1.acknowledgeIfMatches("coll","book",older),"stale audio ack erased newer record");\n    ok(audio2.load("coll","book").orElseThrow().equals(newer),"newer audio lost");\n    ok(audio2.acknowledgeIfMatches("coll","book",newer),"audio ack failed");\n    ok(audio1.load("coll","book").isEmpty(),"audio still pending");\n    var corrupt=text1.prepare("coll","bad",null,a,100);\n    Files.writeString(text1.file("coll","bad"),"bad");\n    try {text1.acknowledgeIfMatches("coll","bad",corrupt); throw new AssertionError("damaged text erased");}\n    catch (IOException expected) { checks++; }\n    ok(Files.readString(text1.file("coll","bad")).equals("bad"),"damaged evidence altered");\n    var acorrupt=audio1.prepare("coll","bad",null,new AudioPosition(10,0,0),2);\n    Files.writeString(audio1.file("coll","bad"),"bad");\n    try {audio1.acknowledgeIfMatches("coll","bad",acorrupt); throw new AssertionError("damaged audio erased");}\n    catch (IOException expected) { checks++; }\n    ok(Files.readString(audio1.file("coll","bad")).equals("bad"),"damaged audio altered");\n    var aempty=audio1.prepare("coll","empty", "", new AudioPosition(3,0,0), 1);\n    ok(audio2.acknowledgeIfMatches("coll","empty",aempty), "empty-base audio acknowledge failed");\n    // Parallel updates on the same book, through different journal instances.\n    var executor=Executors.newFixedThreadPool(6);\n    var jobs=new ArrayList<Future<?>>();\n    for(int t=0;t<6;t++) { final int thread=t;\n      jobs.add(executor.submit(()->{ try {\n        for(int i=0;i<28;i++) {\n          var pos=new ReaderPosition(0,thread*100+i,0,0);\n          text1.prepare("coll","stress",null,pos,10000);\n          var found=text2.load("coll","stress");\n          if (found.isEmpty()) throw new AssertionError("record absent during safe replace");\n        }\n      } catch (Exception e) {throw new RuntimeException(e);} }));\n    }\n    for(var job:jobs)job.get(); executor.shutdown();\n    ok(text1.load("coll","stress").isPresent(),"last stress record corrupted");\n    ok(Files.size(text1.file("coll","stress"))<4096,"oversized stress record");\n    // OS lock must block another JVM (bounded timeout rather than indefinitely).\n    Path pending=text1.file("coll","crossproc");\n    var previous=text1.prepare("coll","crossproc",null,a,100);\n    var lockFile=pending.getParent().resolve(".reader-recovery-"+pending.getFileName().toString().substring(0,2)+".lock");\n    Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),\n       "-cp", System.getProperty("java.class.path"), RecoveryGuardSmoke.class.getName(),"hold", lockFile.toString())\n      .redirectErrorStream(true).start();\n    try(var in=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()))) {\n      ok("LOCKED".equals(in.readLine()),"child failed to acquire cross-process lock");\n      long start=System.nanoTime();\n      try {text2.load("coll","crossproc");throw new AssertionError("OS file lock not respected");}\n      catch (IOException expected) {checks++;}\n      long waited=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);\n      ok(waited>=2500 && waited<4000,"timeout was not bounded: "+waited);\n    } finally {child.waitFor(10, TimeUnit.SECONDS);if(child.isAlive())child.destroyForcibly();}\n    ok(text1.load("coll","crossproc").orElseThrow().equals(previous),"record damaged by lock wait");\n    // Lockfiles are bounded, no one-lock-file-per-book growth.\n    for(int i=0;i<325;i++) text1.prepare("c","volume"+i,null,a,100);\n    try(var entries=Files.list(dir.resolve("text"))) {\n      long locks=entries.filter(p->p.getFileName().toString().endsWith(".lock")).count();\n      ok(locks<=256,"lock files unbounded: "+locks);\n    }\n    System.out.println("PASS recovery guard checks="+checks+" plus concurrent writes=168 plus 325 identities");\n  }\n}\n'


def run() -> None:
    with tempfile.TemporaryDirectory(prefix="mhl-reader-recovery-test-") as folder:
        tmp = Path(folder)
        component = tmp / "stub/org/springframework/stereotype/Component.java"
        component.parent.mkdir(parents=True)
        component.write_text("package org.springframework.stereotype; public @interface Component {}")
        paths = tmp / "stub/com/myhomelibcorp/shared/util/AppPaths.java"
        paths.parent.mkdir(parents=True)
        paths.write_text('package com.myhomelibcorp.shared.util; public class AppPaths { public static java.nio.file.Path dataDir() { return java.nio.file.Path.of(System.getProperty("java.io.tmpdir")); } }')
        smoke = tmp / "RecoveryGuardSmoke.java"
        smoke.write_text(SMOKE_SOURCE)
        classes = tmp / "classes"
        classes.mkdir()
        source_files = [component, paths, smoke,
            SHARED / "SafeFilePrimitives.java", SHARED / "NoLinkPathSafety.java", SHARED / "AtomicFileSupport.java",
            READER / "api/ReaderPosition.java", READER / "audio/AudioPosition.java",
            UI / "ReaderRecoveryFileGuard.java", UI / "ReaderPositionRecoveryJournal.java",
            UI / "ReaderAudioRecoveryJournal.java"]
        subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(classes),
                        *[str(p) for p in source_files]], check=True, timeout=45)
        subprocess.run(["java", "-cp", str(classes),
                        "com.myhomelibcorp.ui.reader.RecoveryGuardSmoke"], check=True, timeout=65)


if __name__ == "__main__":
    run()
