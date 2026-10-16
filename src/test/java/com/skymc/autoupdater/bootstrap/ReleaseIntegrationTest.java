package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

@Tag("release")
class ReleaseIntegrationTest {
    @TempDir Path game;
    Path release() { return Path.of(System.getProperty("updater.release")); }
    @Test void regularJarIsTheSameCompleteDistribution() throws Exception {
        Path regular = release().resolveSibling(release().getFileName().toString().replace("-release.jar", ".jar"));
        assertEquals(-1, Files.mismatch(release(), regular));
    }
    String java() { return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(); }
    @Test void obfuscatedHelperRemovesOnlyAfterExitAndRetainsTheOriginal() throws Exception {
        Files.createDirectories(game.resolve("mods")); Path old=game.resolve("mods/old.jar"); Files.writeString(old,"original mod");
        PendingInstall.recordBatch(game,Map.of(),Map.of("old.jar",ModDownloader.sha1Of(old.toFile())));
        String backupId=PendingInstall.entries(game).getAsJsonObject("old.jar").get("backupId").getAsString();
        Process parent=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command","Start-Sleep -Seconds 120").start();
        Process helper=null;
        try {
            helper=startHelper(parent.pid(),List.of());
            awaitFile(StateFiles.root(game).resolve("ready-"+parent.pid()+".json"),10);
            JsonObject request=new JsonObject(); request.addProperty("sessionId","test-session"); request.addProperty("relaunch",false);
            StateFiles.write(StateFiles.root(game).resolve("install-"+parent.pid()+".json"),request);
            Thread.sleep(500); assertTrue(Files.exists(old),"Even an early install request must not remove a live mod");
            parent.destroyForcibly(); assertTrue(parent.waitFor(10,TimeUnit.SECONDS)); assertTrue(helper.waitFor(20,TimeUnit.SECONDS));
            assertFalse(Files.exists(old)); assertFalse(PendingInstall.hasPending(game));
            assertEquals("original mod",Files.readString(StateFiles.root(game).resolve("removed-mods/"+backupId+"/old.jar")));
            JsonObject result=StateFiles.read(StateFiles.root(game).resolve("result-"+parent.pid()+".json"));
            assertTrue(result.get("installed").getAsBoolean()); assertFalse(result.get("relaunched").getAsBoolean());
        } finally {
            parent.destroyForcibly(); parent.waitFor(10,TimeUnit.SECONDS);
            if(helper!=null && helper.isAlive()) { helper.destroyForcibly(); helper.waitFor(10,TimeUnit.SECONDS); }
        }
    }
    @Test void releaseContainsServiceRelocatedGsonAndOnlyEmbeddedModMetadata() throws Exception {
        try (JarFile jar = new JarFile(release().toFile())) {
            assertNotNull(jar.getJarEntry("META-INF/services/net.minecraftforge.forgespi.locating.IModLocator"));
            assertNotNull(jar.getJarEntry("com/skymc/autoupdater/bootstrap/UpdaterLocator.class"));
            assertNotNull(jar.getJarEntry("com/skymc/autoupdater/bootstrap/InstallerHelper.class"));
            assertNotNull(jar.getJarEntry("com/skymc/autoupdater/bootstrap/internal/gson/JsonParser.class"));
            assertNull(jar.getJarEntry("com/google/gson/JsonParser.class"));
            assertNull(jar.getJarEntry("META-INF/mods.toml"));
            boolean metadata = false, entrypoint = false;
            try (ZipInputStream embedded = new ZipInputStream(jar.getInputStream(jar.getJarEntry("embedded/automodupdater-client.jar")))) {
                for (var entry = embedded.getNextEntry(); entry != null; entry = embedded.getNextEntry()) {
                    if (entry.getName().equals("META-INF/mods.toml")) {
                        String text = new String(embedded.readAllBytes(), StandardCharsets.UTF_8);
                        assertTrue(text.contains("version=\"2.1\"")); assertTrue(text.contains("IGNORE_ALL_VERSION")); metadata = true;
                    }
                    if (entry.getName().equals("com/skymc/autoupdater/AutoUpdaterMod.class")) entrypoint = true;
                }
            }
            assertTrue(metadata); assertTrue(entrypoint);
        }
    }
    @Test void obfuscatedHelperWaitsForWindowsLockThenInstallsAndRelaunches() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Files.createDirectories(game.resolve("mods")); Files.createDirectories(StateFiles.root(game).resolve("staging"));
        Path target = game.resolve("mods/sample.jar"); Files.writeString(target, "old");
        Path staged = StateFiles.root(game).resolve("staging/sample.jar"); Files.writeString(staged, "new");
        PendingInstall.record(game, "sample.jar", ModDownloader.sha1Of(staged.toFile()));
        Path held = game.resolve("lock-ready");
        String ps = "$s=[IO.File]::Open('" + target.toString().replace("'", "''")
                + "',[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None);"
                + "[IO.File]::WriteAllText('" + held.toString().replace("'", "''") + "','ready');Start-Sleep -Seconds 120";
        Process parent = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps).start();
        Process helper = null;
        try {
            awaitFile(held, 10);
            assertEquals(0, PendingInstall.install(game)); assertTrue(PendingInstall.hasPending(game));
            Path marker = game.resolve("relaunched.txt");
            List<String> command = List.of(java(), "-cp", Path.of(RelaunchFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                    RelaunchFixture.class.getName(), marker.toString());
            helper = startHelper(parent.pid(), command);
            awaitFile(StateFiles.root(game).resolve("ready-" + parent.pid() + ".json"), 10);
            Thread.sleep(250); assertTrue(helper.isAlive()); assertFalse(Files.exists(marker));
            JsonObject request = new JsonObject(); request.addProperty("sessionId", "test-session"); request.addProperty("relaunch", true);
            StateFiles.write(StateFiles.root(game).resolve("request-" + parent.pid() + ".json"), request);
            parent.destroyForcibly(); assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
            assertTrue(helper.waitFor(20, TimeUnit.SECONDS));
            awaitFile(marker, 10);
            assertEquals("new", Files.readString(target)); assertFalse(PendingInstall.hasPending(game));
            JsonObject result = StateFiles.read(StateFiles.root(game).resolve("result-" + parent.pid() + ".json"));
            assertTrue(result.get("installed").getAsBoolean()); assertTrue(result.get("relaunched").getAsBoolean());
            var child = ProcessHandle.of(result.get("childPid").getAsLong());
            if (child.isPresent()) child.get().onExit().get(10, TimeUnit.SECONDS);
        } finally {
            parent.destroyForcibly(); parent.waitFor(10, TimeUnit.SECONDS);
            if (helper != null && helper.isAlive()) { helper.destroyForcibly(); helper.waitFor(10, TimeUnit.SECONDS); }
        }
    }
    @Test void manualExitInstallsWithoutLaunchingAnotherGame() throws Exception {
        Files.createDirectories(StateFiles.root(game).resolve("staging"));
        Path staged = StateFiles.root(game).resolve("staging/sample.jar"); Files.writeString(staged, "new");
        PendingInstall.record(game, "sample.jar", ModDownloader.sha1Of(staged.toFile()));
        Process helper = startHelper(Long.MAX_VALUE, List.of());
        try {
            assertTrue(helper.waitFor(20, TimeUnit.SECONDS));
            assertEquals("new", Files.readString(game.resolve("mods/sample.jar")));
            JsonObject result = StateFiles.read(StateFiles.root(game).resolve("result-" + Long.MAX_VALUE + ".json"));
            assertTrue(result.get("installed").getAsBoolean()); assertFalse(result.get("relaunched").getAsBoolean());
        } finally { if (helper.isAlive()) helper.destroyForcibly(); }
    }
    @Test void releaseCommandCaptureWorksOnWindowsAndDoesNotExposeTokens() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        List<String> captured = LaunchCommand.capture();
        assertTrue(Files.isRegularFile(Path.of(captured.get(0)))); assertTrue(captured.size() > 1);
    }
    @Test void obsoleteHelperCannotInstallOrRelaunchSupersededSession() throws Exception {
        Path staged=StateFiles.root(game).resolve("staging/sample.jar"); Files.createDirectories(staged.getParent()); Files.writeString(staged,"new");
        PendingInstall.record(game,"sample.jar",ModDownloader.sha1Of(staged.toFile()));
        JsonObject owner=new JsonObject(); owner.addProperty("sessionId","newer-session"); StateFiles.write(StateFiles.root(game).resolve("installer-owner.json"),owner);
        Process helper=startHelper(Long.MAX_VALUE,List.of());
        try {
            assertTrue(helper.waitFor(20,TimeUnit.SECONDS));
            JsonObject result=StateFiles.read(StateFiles.root(game).resolve("result-"+Long.MAX_VALUE+".json"));
            assertTrue(result.get("superseded").getAsBoolean()); assertFalse(result.get("relaunched").getAsBoolean());
            assertFalse(Files.exists(game.resolve("mods/sample.jar"))); assertTrue(PendingInstall.hasPending(game));
        } finally {if(helper.isAlive())helper.destroyForcibly();}
    }
    Process startHelper(long pid, List<String> command) throws Exception {
        JsonObject payload = new JsonObject(); payload.addProperty("pid", pid); payload.addProperty("sessionId", "test-session");
        payload.addProperty("gameDir", game.toString()); payload.addProperty("cwd", game.toString());
        JsonArray argv = new JsonArray(); command.forEach(argv::add); payload.add("command", argv);
        Process helper = new ProcessBuilder(java(), "-cp", release().toString(), InstallerHelper.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (OutputStream stdin = helper.getOutputStream()) { stdin.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
        return helper;
    }
    static void awaitFile(Path file, int seconds) throws Exception {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!Files.exists(file) && System.nanoTime() < end) Thread.sleep(50);
        assertTrue(Files.exists(file), "Missing fixture output: " + file.getFileName());
    }
}
