#!/usr/bin/env python3
"""Compile the real Reader transaction guard and test concurrency across JVMs.
No Maven, production DB, or network dependency. Java 21 required.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "myhomelib-ui/src/main/java/com/myhomelibcorp/ui/reader"
SHARED = ROOT / "myhomelib-shared/src/main/java/com/myhomelibcorp/shared/util"
SMOKE = r'''package com.myhomelibcorp.ui.reader;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ReaderTransactionSmoke {
    private static int assertions=0;
    private static void ok(boolean yes,String detail) { assertions++; if (!yes) throw new AssertionError(detail); }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[1]);
        var guard=new ReaderProgressTransactionGuard(root);
        if (args[0].equals("child")) {
            guard.withLock("coll","shared-book", () -> {
                System.out.println("ACQUIRED"); System.out.flush();
                try { Thread.sleep(1800); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                return null;
            });
            return;
        }
        Path counter=root.resolve("value");
        Files.createDirectories(root);
        Files.writeString(counter,"0");
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp", System.getProperty("java.class.path"),
                ReaderTransactionSmoke.class.getName(), "child", root.toString())
                .redirectErrorStream(true).start();
        try (var input=new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            ok("ACQUIRED".equals(input.readLine()),"child could not acquire lock");
            long begin=System.nanoTime();
            guard.withLock("coll","shared-book", () -> {
                long waited=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin);
                ok(waited>=1300,"cooperating JVM entered critical section too early: "+waited);
                Files.writeString(counter,"committed");
                return null;
            });
            ok("committed".equals(Files.readString(counter)), "critical section failed");
        } finally {
            child.waitFor(5, TimeUnit.SECONDS);
            if (child.isAlive()) child.destroyForcibly();
        }
        var fast=new ReaderProgressTransactionGuard(root,TimeUnit.MILLISECONDS.toNanos(150));
        var started=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            Future<?> task=pool.submit(() -> {
                try {
                    guard.withLock("coll","shared-book", () -> {
                        started.countDown();
                        try { Thread.sleep(400); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return null;
                    });
                } catch(IOException e) { throw new IllegalStateException(e); }
            });
            ok(started.await(2,TimeUnit.SECONDS),"worker not started");
            try {
                fast.withLock("coll","shared-book", () -> {
                    Files.writeString(counter,"bad"); return null;
                });
                throw new AssertionError("failed to reject blocked writer");
            } catch(IOException expected) { assertions++; }
            ok("committed".equals(Files.readString(counter)),"timeout wrote data");
            task.get(5,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();}
        for(int i=0;i<400;i++) {
            guard.withLock("other","book-"+i, () -> null);
        }
        try(var files=Files.list(root)) {
            long count=files.filter(f->f.getFileName().toString().endsWith(".lock")).count();
            ok(count<=256,"unbounded lock files: "+count);
        }
        Path external=Files.createDirectory(root.resolve("external"));
        Path link=root.resolve("symlink");
        try {
            Files.createSymbolicLink(link,external);
            try {
                new ReaderProgressTransactionGuard(link).withLock("coll","book",()->null);
                throw new AssertionError("followed symlink");
            } catch(IOException expected) { assertions++; }
        } catch(UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            System.out.println("SKIP symlink test (filesystem unsupported)");
        }
        System.out.println("PASS Reader transaction guard checks="+assertions+" across 2 JVMs and 400 books");
    }
}'''

def run():
    with tempfile.TemporaryDirectory(prefix="mhl-reader-txn-") as temp:
        base=Path(temp)
        stub=base/"stubs/com/myhomelibcorp/shared/util/AppPaths.java"
        stub.parent.mkdir(parents=True)
        stub.write_text('package com.myhomelibcorp.shared.util; public final class AppPaths { public static java.nio.file.Path dataDir() { return java.nio.file.Path.of(System.getProperty("java.io.tmpdir")); } }')
        smoke=base/"ReaderTransactionSmoke.java"
        smoke.write_text(SMOKE)
        classes=base/"classes"
        classes.mkdir()
        files=[stub, smoke,
              SHARED/"SafeFilePrimitives.java", SHARED/"NoLinkPathSafety.java", SHARED/"AtomicFileSupport.java",
              UI/"ReaderProgressTransactionGuard.java"]
        subprocess.run(["javac","-encoding","UTF-8","-d",str(classes),*[str(f) for f in files]],check=True,timeout=45)
        subprocess.run(["java","-cp",str(classes),"com.myhomelibcorp.ui.reader.ReaderTransactionSmoke","parent",str(base/"locks")],check=True,timeout=35)

if __name__=='__main__':
    run()
