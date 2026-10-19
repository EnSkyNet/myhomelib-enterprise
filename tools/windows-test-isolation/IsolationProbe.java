package com.myhomelibcorp.testisolation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.prefs.Preferences;

public final class IsolationProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("mhl.isolation.root"));
        for (String key : new String[]{"user.home", "java.io.tmpdir", "myhomelib.dataDir", "myhomelib.launchDir",
                "app.metadata.db-path", "myhomelib.logDir", "logging.file.name", "app.search.index-path"}) {
            if (!Path.of(System.getProperty(key)).toAbsolutePath().normalize().startsWith(root)) {
                throw new AssertionError("Path escaped: " + key);
            }
        }
        Preferences user = Preferences.userRoot();
        if (!user.getClass().getName().startsWith(MemoryPreferencesFactory.class.getName())) {
            throw new AssertionError("Unexpected preferences implementation");
        }
        if (user.get("parentSentinel", null) != null) throw new AssertionError("Preferences leaked between JVMs");
        Preferences child = user.node("probe/child");
        child.put("key", "value");
        child.putInt("number", 21);
        child.flush();
        child.sync();
        if (!"value".equals(child.get("key", null)) || child.getInt("number", 0) != 21
                || !Arrays.asList(child.keys()).contains("key")) throw new AssertionError("Read/write contract");
        if (Preferences.systemRoot().node("probe/child").get("key", null) != null) {
            throw new AssertionError("User/system roots are not independent");
        }
        child.removeNode();
        if (user.nodeExists("probe/child")) throw new AssertionError("Node removal contract");
        if (user.node("probe/child").get("key", null) != null) throw new AssertionError("Deleted value resurrected");
        Path temporary = Files.createTempFile("isolation-probe-", ".txt");
        if (!temporary.startsWith(root)) throw new AssertionError("Temporary path escaped");
        if (args.length == 0) {
            user.put("parentSentinel", "must-not-leak");
            Process fork = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", System.getProperty("java.class.path"), IsolationProbe.class.getName(), "child")
                    .inheritIO().start();
            if (!fork.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { fork.destroyForcibly(); throw new AssertionError("Child timeout"); }
            if (fork.exitValue() != 0) throw new AssertionError("Inherited child isolation failed");
        }
        System.out.println("ISOLATION_PROBE_PASS pid=" + ProcessHandle.current().pid());
    }
}
