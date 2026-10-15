package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DownloaderTest {
    @TempDir Path game;
    ConfigFixture fixture;
    @BeforeEach void start() throws Exception {
        fixture = new ConfigFixture(); Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/automodupdater.json"),"{\"manifestUrl\":\""+fixture.base+"/manifest\",\"autoRestart\":false}");
    }
    @AfterEach void stop() { fixture.close(); }
    void run() { ModDownloader.runSync(game.toFile(),game.resolve("mods").toFile()); }
    Path staged(String name) { return StateFiles.root(game).resolve("staging/"+name); }
    @Test void modsAreStagedAndOnlyRecordedVerifiedEntriesAreInstalled() throws Exception {
        fixture.publish(ConfigFixture.array(fixture.mod("sample.jar","new")),new JsonArray()); run();
        assertEquals(1,UpdateState.newModsDownloaded); assertFalse(Files.exists(game.resolve("mods/sample.jar")));
        Files.writeString(staged("orphan.jar"),"orphan"); Files.writeString(staged("partial.jar.part"),"partial");
        assertEquals(1,PendingInstall.install(game)); assertEquals("new",Files.readString(game.resolve("mods/sample.jar")));
        assertFalse(Files.exists(game.resolve("mods/orphan.jar"))); assertFalse(PendingInstall.hasPending(game));
        run(); assertEquals(0,UpdateState.newModsDownloaded); assertEquals(1,fixture.downloads.get());
    }
    @Test void configOnlyIsAppliedEarlyWithoutRestartAndUpdaterSettingsRemain() throws Exception {
        String settings = Files.readString(game.resolve("config/automodupdater.json"));
        fixture.publish(new JsonArray(),ConfigFixture.array(fixture.folder("sample",java.util.Map.of("data.txt","new")))); run();
        assertEquals("new",Files.readString(game.resolve("config/sample/data.txt")));
        assertEquals(0,UpdateState.newModsDownloaded); assertEquals(1,UpdateState.configEntriesUpdated);
        assertEquals(settings,Files.readString(game.resolve("config/automodupdater.json")));
    }
    @Test void modAndConfigReleaseKeepsOldConfigUntilModInstallation() throws Exception {
        Files.writeString(game.resolve("config/sample.toml"),"old-config");
        fixture.publish(ConfigFixture.array(fixture.mod("sample.jar","new-mod")),ConfigFixture.array(fixture.file("sample.toml","new-config"))); run();
        assertEquals(1,UpdateState.newModsDownloaded); assertEquals("old-config",Files.readString(game.resolve("config/sample.toml")));
        PendingInstall.install(game); new ConfigInstaller(game,null).recover(true);
        assertEquals("new-config",Files.readString(game.resolve("config/sample.toml")));
    }
    @Test void configFailureDoesNotArmDownloadedMods() throws Exception {
        JsonObject bad = fixture.file("sample.toml","new"); bad.addProperty("sha256","0".repeat(64));
        fixture.publish(ConfigFixture.array(fixture.mod("sample.jar","new")),ConfigFixture.array(bad)); run();
        assertEquals(0,UpdateState.newModsDownloaded); assertTrue(Files.exists(staged("sample.jar")));
        assertFalse(PendingInstall.hasPending(game)); assertFalse(Files.exists(game.resolve("mods/sample.jar")));
    }
    @Test void failedModEntryDoesNotArmPartialRelease() throws Exception {
        JsonObject bad = fixture.mod("bad.jar","bad"); bad.addProperty("url",fixture.base+"/missing");
        fixture.publish(ConfigFixture.array(fixture.mod("good.jar","good"),bad),new JsonArray()); run();
        assertEquals(0,UpdateState.newModsDownloaded); assertFalse(PendingInstall.hasPending(game));
        assertTrue(Files.exists(staged("good.jar"))); assertFalse(Files.exists(game.resolve("mods/good.jar")));
    }
    @Test void existingHashlessAndUnpublishedModsAreRetained() throws Exception {
        Files.createDirectories(game.resolve("mods")); Files.writeString(game.resolve("mods/sample.jar"),"old");
        Files.writeString(game.resolve("mods/unlisted.jar"),"user"); JsonObject entry = fixture.mod("sample.jar","new"); entry.remove("sha1");
        fixture.publish(ConfigFixture.array(entry),new JsonArray()); run();
        assertEquals("old",Files.readString(game.resolve("mods/sample.jar"))); assertEquals("user",Files.readString(game.resolve("mods/unlisted.jar")));
        assertEquals(0,fixture.downloads.get());
    }
    @Test void stagedContentIsReusedAndCountersResetAfterBadManifest() throws Exception {
        Files.createDirectories(staged("sample.jar").getParent()); Files.writeString(staged("sample.jar"),"new");
        fixture.publish(ConfigFixture.array(fixture.mod("sample.jar","new")),new JsonArray()); run();
        assertEquals(1,UpdateState.newModsDownloaded); assertEquals(0,fixture.downloads.get());
        fixture.manifest="bad json"; run(); assertEquals(0,UpdateState.newModsDownloaded); assertTrue(UpdateState.downloadedNames.isEmpty());
        assertFalse(UpdateState.errorMessage.isEmpty());
    }
    @Test void unversionedAndWrongVersionPublicationsAreRejectedBeforeDownloads() throws Exception {
        fixture.manifest="["+fixture.mod("old.jar","old")+"]"; run(); assertEquals(0,fixture.downloads.get());
        fixture.publish(ConfigFixture.array(fixture.mod("old.jar","old")),new JsonArray());
        fixture.manifest=fixture.manifest.replace("\"minecraftVersion\":\"1.20.1\"","\"minecraftVersion\":\"1.21.1\""); run();
        assertEquals(0,fixture.downloads.get()); assertFalse(PendingInstall.hasPending(game));
    }
    @Test void bomAndRelativeRedirectWorkForVersionedPublication() throws Exception {
        fixture.server.createContext("/redirect",e->{e.getResponseHeaders().add("Location","/mod/sample.jar");e.sendResponseHeaders(307,-1);e.close();});
        JsonObject entry=fixture.mod("sample.jar","new"); entry.addProperty("url",fixture.base+"/redirect");
        fixture.publish(ConfigFixture.array(entry),new JsonArray()); fixture.manifest="\uFEFF"+fixture.manifest; run();
        assertEquals(1,UpdateState.newModsDownloaded);
    }
    @Test void tamperedStagingAndHashMismatchCannotInstall() throws Exception {
        JsonObject entry=fixture.mod("sample.jar","new"); entry.addProperty("sha1","0".repeat(40));
        fixture.publish(ConfigFixture.array(entry),new JsonArray()); run(); assertFalse(PendingInstall.hasPending(game));
        assertFalse(Files.exists(staged("sample.jar.part")));
        Files.writeString(staged("sample.jar"),"new"); PendingInstall.record(game,"sample.jar",ModDownloader.sha1Of(staged("sample.jar").toFile()));
        Files.writeString(staged("sample.jar"),"tampered"); assertEquals(0,PendingInstall.install(game)); assertTrue(PendingInstall.hasPending(game));
    }
    @Test void offlineManifestDoesNotCrashAndResetsCounters() throws Exception {
        fixture.close(); UpdateState.newModsDownloaded=10; run(); assertEquals(0,UpdateState.newModsDownloaded);
    }
    @Test void configPreservesCustomSettingsAndMalformedConfigFallsBack() throws Exception {
        String custom="https://example.test/server-mods.json";
        Files.writeString(game.resolve("config/automodupdater.json"),"{\"manifestUrl\":\""+custom+"\",\"autoRestart\":false,\"extra\":7}");
        Config.load(game.toFile()); assertEquals(custom,Config.manifestUrl); assertFalse(Config.autoRestart);
        assertTrue(Files.readString(game.resolve("config/automodupdater.json")).contains("\"extra\":7"));
        Files.writeString(game.resolve("config/automodupdater.json"),"broken"); Config.load(game.toFile()); assertTrue(Config.autoRestart);
        Files.delete(game.resolve("config/automodupdater.json")); Config.load(game.toFile());
        assertTrue(Files.readString(game.resolve("config/automodupdater.json")).contains("mcmods2/main/server-mods.json"));
    }
}
