package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigInstallerTest {
    @TempDir Path game;
    ConfigFixture fixture;
    @BeforeEach void start() throws Exception { fixture = new ConfigFixture(); }
    @AfterEach void stop() { fixture.close(); }
    ConfigInstaller installer() throws Exception { return new ConfigInstaller(game,null); }
    void apply(JsonObject... entries) throws Exception {
        ConfigInstaller c = installer(); c.arm(c.prepare(ConfigFixture.array(entries)),Map.of()); c.recover(true);
    }
    @Test void missingRootFilesUnicodeHiddenAndEmptyDirectoriesAreInstalled() throws Exception {
        apply(fixture.folder("Ömer space",Map.of("nested/.hidden","secret","root.json","new"),"empty"),fixture.file("example.toml","value=1"));
        assertEquals("secret",Files.readString(game.resolve("config/Ömer space/nested/.hidden")));
        assertEquals("value=1",Files.readString(game.resolve("config/example.toml")));
        assertTrue(Files.isDirectory(game.resolve("config/Ömer space/empty")));
        assertFalse(Files.exists(StateFiles.root(game).resolve("config-backups")));
    }
    @Test void changedFolderIsCompletelyBackedUpAndUnpublishedRootsRemain() throws Exception {
        Files.createDirectories(game.resolve("config/published"));
        Files.writeString(game.resolve("config/published/old.txt"),"user-data");
        Files.writeString(game.resolve("config/unlisted.toml"),"untouched");
        apply(fixture.folder("published",Map.of("new.txt","new")));
        assertFalse(Files.exists(game.resolve("config/published/old.txt")));
        assertEquals("new",Files.readString(game.resolve("config/published/new.txt")));
        try (var backups = Files.walk(StateFiles.root(game).resolve("config-backups"))) {
            Path old = backups.filter(p -> p.getFileName().toString().equals("old.txt")).findFirst().orElseThrow();
            assertEquals("user-data",Files.readString(old));
        }
        assertEquals("untouched",Files.readString(game.resolve("config/unlisted.toml")));
    }
    @Test void unchangedContentCausesNoDownloadBackupOrRewriteAndLocalEditsAreResetFromCache() throws Exception {
        JsonObject entry = fixture.folder("published",Map.of("settings.json","default"),"empty"); apply(entry);
        Path target = game.resolve("config/published/settings.json"); var timestamp = Files.getLastModifiedTime(target);
        apply(entry); assertEquals(1,fixture.downloads.get()); assertEquals(timestamp,Files.getLastModifiedTime(target));
        assertFalse(Files.exists(StateFiles.root(game).resolve("config-backups")));
        Files.writeString(target,"user-edited"); Files.writeString(game.resolve("config/published/extra.txt"),"extra");
        apply(entry); assertEquals("default",Files.readString(target)); assertEquals(1,fixture.downloads.get());
        assertFalse(Files.exists(game.resolve("config/published/extra.txt")));
        assertTrue(Files.exists(StateFiles.root(game).resolve("config-backups")));
    }
    @Test void missingFileOrDirectoryTriggersExactReplacement() throws Exception {
        JsonObject entry = fixture.folder("published",Map.of("file","data"),"empty"); apply(entry);
        Files.delete(game.resolve("config/published/empty")); apply(entry);
        assertTrue(Files.isDirectory(game.resolve("config/published/empty")));
        Files.delete(game.resolve("config/published/file")); apply(entry);
        assertEquals("data",Files.readString(game.resolve("config/published/file")));
        assertEquals(1,fixture.downloads.get());
    }
    @Test void fileDirectoryTypeChangesKeepOriginalBackup() throws Exception {
        apply(fixture.file("same","old")); apply(fixture.folder("same",Map.of("nested","new")));
        assertEquals("new",Files.readString(game.resolve("config/same/nested")));
        apply(fixture.file("same","new-file")); assertEquals("new-file",Files.readString(game.resolve("config/same")));
    }
    @Test void corruptHashOrHttpFailureDoesNotTouchAnyTargetInBatch() throws Exception {
        apply(fixture.file("a.toml","old-a"),fixture.file("b.toml","old-b"));
        JsonObject bad = fixture.file("b.toml","new-b"); bad.addProperty("sha256","0".repeat(64));
        final JsonObject wrongHash = bad;
        ConfigInstaller c = installer(); assertThrows(IOException.class,()->c.prepare(ConfigFixture.array(fixture.file("a.toml","new-a"),wrongHash)));
        assertEquals("old-a",Files.readString(game.resolve("config/a.toml")));
        assertEquals("old-b",Files.readString(game.resolve("config/b.toml")));
        bad = fixture.file("b.toml","new-b"); bad.addProperty("url",fixture.base + "/missing");
        final JsonObject missing = bad; assertThrows(IOException.class,()->c.prepare(ConfigFixture.array(missing)));
        assertEquals("old-b",Files.readString(game.resolve("config/b.toml")));
    }
    @Test void failureAfterFirstReplacementRollsBackWholeBatch() throws Exception {
        apply(fixture.file("a.toml","old-a"),fixture.file("b.toml","old-b"));
        ConfigInstaller c = installer(); c.arm(c.prepare(ConfigFixture.array(fixture.file("a.toml","new-a"),fixture.file("b.toml","new-b"))),Map.of());
        ConfigInstaller faulty = new ConfigInstaller(game,null,(from,to)-> {
            if (to.equals(game.resolve("config/b.toml")) && from.toString().contains("entries")) throw new IOException("simulated disk/move failure");
            Files.move(from,to);
        });
        assertThrows(IOException.class,()->faulty.recover(true));
        assertEquals("old-a",Files.readString(game.resolve("config/a.toml")));
        assertEquals("old-b",Files.readString(game.resolve("config/b.toml")));
        assertFalse(Files.exists(StateFiles.root(game).resolve("config-transaction.json")));
    }
    @Test void newTargetsAreRemovedFromConfigWhenBatchRollsBack() throws Exception {
        ConfigInstaller c = installer(); c.arm(c.prepare(ConfigFixture.array(fixture.file("a","new-a"),fixture.file("b","new-b"))),Map.of());
        ConfigInstaller faulty = new ConfigInstaller(game,null,(from,to)-> { if(to.equals(game.resolve("config/b"))) throw new IOException("failure"); Files.move(from,to); });
        assertThrows(IOException.class,()->faulty.recover(true));
        assertFalse(Files.exists(game.resolve("config/a"))); assertFalse(Files.exists(game.resolve("config/b")));
    }
    @Test void interruptedBackupAndInstallRecoverOnNextStartup() throws Exception {
        apply(fixture.file("a","old")); ConfigInstaller c = installer();
        var prepared = c.prepare(ConfigFixture.array(fixture.file("a","new"))); c.arm(prepared,Map.of());
        Path journal = StateFiles.root(game).resolve("config-transaction.json"); JsonObject tx = StateFiles.read(journal);
        JsonObject item = tx.getAsJsonArray("entries").get(0).getAsJsonObject();
        tx.addProperty("phase","applying"); item.addProperty("state","install-intent"); item.addProperty("hadOriginal",true); StateFiles.write(journal,tx);
        Path backup = StateFiles.root(game).resolve("config-backups/" + prepared.id() + "/a"); Files.createDirectories(backup.getParent());
        Files.move(game.resolve("config/a"),backup);
        Files.move(StateFiles.root(game).resolve("config-staging/" + prepared.id() + "/entries/a"),game.resolve("config/a"));
        installer().recover(true); assertEquals("old",Files.readString(game.resolve("config/a"))); assertFalse(Files.exists(journal));
    }
    @Test void configIsDeferredUntilAllRequiredModsAreInstalledEvenAfterJournalWriteCrash() throws Exception {
        apply(fixture.file("a","old")); ConfigInstaller c = installer();
        Path staged = StateFiles.root(game).resolve("staging/new.jar"); Files.createDirectories(staged.getParent()); Files.writeString(staged,"new-mod");
        c.arm(c.prepare(ConfigFixture.array(fixture.file("a","new"))),Map.of("new.jar",ModDownloader.sha1Of(staged.toFile())));
        assertEquals(0,c.recover(false)); assertEquals("old",Files.readString(game.resolve("config/a")));
        assertEquals(0,c.recover(true)); assertTrue(PendingInstall.hasPending(game));
        assertEquals(1,PendingInstall.install(game)); assertEquals(1,c.recover(true));
        assertEquals("new",Files.readString(game.resolve("config/a")));
    }
    @Test void reservedNamesTraversalDuplicateWindowsNamesAndBadInventoryAreRejected() throws Exception {
        for (String name : List.of("automodupdater.json","../outside","C:secret","nul","trailing.","bad\\path","bad\r\nname")) {
            fixture.publish(new JsonArray(),ConfigFixture.array(fixture.file(name,"bad")));
            assertThrows(IOException.class,()->new PackManifest(fixture.manifest));
        }
        fixture.publish(new JsonArray(),ConfigFixture.array(fixture.file("Case","a"),fixture.file("case","b")));
        assertThrows(IOException.class,()->new PackManifest(fixture.manifest));
        JsonObject folder = fixture.folder("valid",Map.of("nested/file","a")); folder.add("directories",new JsonArray());
        fixture.publish(new JsonArray(),ConfigFixture.array(folder)); assertThrows(IOException.class,()->new PackManifest(fixture.manifest));
    }
    @Test void tamperedStagingRollsBackAndProtectedUpdaterConfigSurvives() throws Exception {
        Files.createDirectories(game.resolve("config")); Files.writeString(game.resolve("config/automodupdater.json"),"protected");
        apply(fixture.file("a","old")); ConfigInstaller c = installer(); var p = c.prepare(ConfigFixture.array(fixture.file("a","new"))); c.arm(p,Map.of());
        Files.writeString(StateFiles.root(game).resolve("config-staging/" + p.id() + "/entries/a"),"tampered");
        assertThrows(IOException.class,()->c.recover(true)); assertEquals("old",Files.readString(game.resolve("config/a")));
        assertEquals("protected",Files.readString(game.resolve("config/automodupdater.json")));
    }
    @Test void emptyPublicationDoesNotCreateOrEraseUserConfig() throws Exception {
        ConfigInstaller c = installer(); var p = c.prepare(new JsonArray()); c.arm(p,Map.of()); assertEquals(0,c.recover(true));
        assertFalse(Files.exists(game.resolve("config")));
    }
    @Test void rollbackFailureIsFatalAndNextStartupResumesRestore() throws Exception {
        apply(fixture.file("a","old-a"),fixture.file("b","old-b"));
        ConfigInstaller c = installer(); c.arm(c.prepare(ConfigFixture.array(fixture.file("a","new-a"),fixture.file("b","new-b"))),Map.of());
        ConfigInstaller faulty = new ConfigInstaller(game,null,(from,to)-> {
            if (to.equals(game.resolve("config/b")) && from.toString().contains("entries")) throw new IOException("install failure");
            if (to.equals(game.resolve("config/a")) && from.toString().contains("config-backups")) throw new IOException("restore failure");
            Files.move(from,to);
        });
        assertThrows(ConfigInstaller.RecoveryException.class,()->faulty.recover(true));
        assertTrue(Files.exists(StateFiles.root(game).resolve("config-transaction.json")));
        installer().recover(true);
        assertEquals("old-a",Files.readString(game.resolve("config/a"))); assertEquals("old-b",Files.readString(game.resolve("config/b")));
        assertFalse(Files.exists(StateFiles.root(game).resolve("config-transaction.json")));
    }
    @Test void committedCrashKeepsNewContentAndRestoreIntentCrashKeepsOriginal() throws Exception {
        apply(fixture.file("a","old")); ConfigInstaller c = installer(); var p = c.prepare(ConfigFixture.array(fixture.file("a","new"))); c.arm(p,Map.of());
        Path journal = StateFiles.root(game).resolve("config-transaction.json"); JsonObject tx = StateFiles.read(journal);
        JsonObject entry = tx.getAsJsonArray("entries").get(0).getAsJsonObject(); entry.addProperty("hadOriginal",true); entry.addProperty("state","installed");
        Path backup = StateFiles.root(game).resolve("config-backups/"+p.id()+"/a"); Files.createDirectories(backup.getParent()); Files.move(game.resolve("config/a"),backup);
        Files.move(StateFiles.root(game).resolve("config-staging/"+p.id()+"/entries/a"),game.resolve("config/a"));
        tx.addProperty("phase","committed"); StateFiles.write(journal,tx); installer().recover(true);
        assertEquals("new",Files.readString(game.resolve("config/a"))); assertEquals("old",Files.readString(backup));
        tx.addProperty("phase","rolling-back"); entry.addProperty("state","restore-intent");
        Files.move(game.resolve("config/a"),game.resolve("config/retained-new")); Files.move(backup,game.resolve("config/a"));
        StateFiles.write(journal,tx); installer().recover(true); assertEquals("old",Files.readString(game.resolve("config/a")));
    }
    @Test void unexpectedZipPathsAndExpansionBeyondInventoryAreRejectedBeforeReplacement() throws Exception {
        for (String badName : List.of("../outside","unexpected","allowed")) {
            JsonObject entry = fixture.folder("published",Map.of("allowed","x"));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                zip.putNextEntry(new java.util.zip.ZipEntry(badName)); zip.write("oversized".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
            String hash = ConfigFixture.hash(bytes.toByteArray(),"SHA-256"); fixture.assets.put("/"+hash,bytes.toByteArray());
            entry.addProperty("sha256",hash); entry.addProperty("url",fixture.base+"/"+hash);
            entry.addProperty("size",bytes.size());
            assertThrows(IOException.class,()->installer().prepare(ConfigFixture.array(entry)));
            assertFalse(Files.exists(game.resolve("config/published"))); assertFalse(Files.exists(game.resolve("outside")));
        }
    }
    @Test void windowsJunctionCannotRedirectConfigWritesOutsideRoot() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Path outside = game.resolve("outside"), link = game.resolve("config/linked"); Files.createDirectories(outside); Files.createDirectories(link.getParent());
        Process process = new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes(); assertEquals(0,process.waitFor());
        try {
            assertThrows(IOException.class,()->installer().prepare(ConfigFixture.array(fixture.folder("linked",Map.of("file","new")))));
            assertFalse(Files.exists(outside.resolve("file"))); assertEquals(0,fixture.downloads.get());
        } finally { Files.delete(link); }
    }
    @Test void simultaneousProfileWritersAreBlockedAndJournalCannotBeOverwritten() throws Exception {
        try (ProfileLock first = ProfileLock.acquire(game,0)) {
            assertThrows(IOException.class,()->ProfileLock.acquire(game,25));
        }
        try (ProfileLock next = ProfileLock.acquire(game,0)) { assertNotNull(next); }
        ConfigInstaller c = installer(); c.arm(c.prepare(ConfigFixture.array(fixture.file("a","new"))),Map.of());
        String journal = Files.readString(StateFiles.root(game).resolve("config-transaction.json"));
        assertThrows(IOException.class,()->installer().prepare(ConfigFixture.array(fixture.file("b","new"))));
        assertEquals(journal,Files.readString(StateFiles.root(game).resolve("config-transaction.json")));
    }
    @Test void insufficientDiskSpaceIsRejectedBeforeDownloadOrBackup() throws Exception {
        apply(fixture.file("a","old")); fixture.downloads.set(0);
        ConfigInstaller fullDisk = new ConfigInstaller(game,null,(from,to)->Files.move(from,to),path->0);
        assertThrows(IOException.class,()->fullDisk.prepare(ConfigFixture.array(fixture.file("a","new"))));
        assertEquals(0,fixture.downloads.get()); assertEquals("old",Files.readString(game.resolve("config/a")));
        assertFalse(Files.exists(StateFiles.root(game).resolve("config-backups")));
    }
    @Test void actualWindowsFileLockKeepsOriginalAndSuccessfulRetryUsesCache() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        apply(fixture.file("a","old")); ConfigInstaller c = installer();
        JsonObject entry = fixture.file("a","new"); c.arm(c.prepare(ConfigFixture.array(entry)),Map.of());
        Path target = game.resolve("config/a"), ready = game.resolve("lock-ready"), release = game.resolve("lock-release");
        String script = "$s=[IO.File]::Open('"+target.toString().replace("'","''")+"',[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None);"
                +"[IO.File]::WriteAllText('"+ready.toString().replace("'","''")+"','ready');"
                +"while(-not [IO.File]::Exists('"+release.toString().replace("'","''")+"')){Start-Sleep -Milliseconds 20};$s.Dispose()";
        Process holder = new ProcessBuilder("powershell.exe","-NoProfile","-Command",script).start();
        try {
            ReleaseIntegrationTest.awaitFile(ready,10);
            assertThrows(IOException.class,()->c.recover(true));
        } finally {
            Files.writeString(release,"release");
            if(!holder.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)) {holder.destroyForcibly();holder.waitFor(10,java.util.concurrent.TimeUnit.SECONDS);}
        }
        assertEquals("old",Files.readString(target)); int downloads = fixture.downloads.get();
        apply(entry); assertEquals("new",Files.readString(target)); assertEquals(downloads,fixture.downloads.get());
    }
}
