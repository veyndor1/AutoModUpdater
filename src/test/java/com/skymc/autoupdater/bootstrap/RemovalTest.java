package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RemovalTest {
    @TempDir Path game;
    ConfigFixture server;
    @BeforeEach void setup() throws Exception {
        server=new ConfigFixture(); Files.createDirectories(game.resolve("config")); Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("config/automodupdater.json"),"{\"manifestUrl\":\""+server.base+"/manifest\"}");
        Files.writeString(game.resolve("mods/old.jar"),"original");
    }
    @AfterEach void close() { server.close(); }
    JsonObject removal(String name) {
        JsonObject e=new JsonObject(); e.addProperty("fileName",name); e.addProperty("name","Eski mod");
        e.addProperty("reason","Yeni paketle uyumsuz"); return e;
    }
    void publish(JsonArray mods,JsonArray configs,JsonArray removals) {
        server.publish(mods,configs); JsonObject root=JsonParser.parseString(server.manifest).getAsJsonObject();
        root.add("removeMods",removals); server.manifest=root.toString();
    }
    UpdateReport run() { return ModDownloader.download(game.toFile(),game.resolve("mods").toFile(),UpdateProgress.NONE); }
    @Test void removalOnlyIsDeferredBackedUpAndIdempotent() throws Exception {
        Files.writeString(game.resolve("mods/user.jar"),"user mod");
        publish(new JsonArray(),new JsonArray(),ConfigFixture.array(removal("old.jar"),removal("missing.jar")));
        List<String> events=new ArrayList<>();
        UpdateReport report=ModDownloader.download(game.toFile(),game.resolve("mods").toFile(),new UpdateProgress() {
            @Override public void item(Kind k,String id,String name,Phase phase,long b,long t) { events.add(id+":"+phase); }
        });
        assertTrue(report.successful(),report.error()); assertTrue(report.changed()); assertEquals(1,report.removals()); assertEquals(0,report.mods());
        assertEquals(1,StateFiles.snapshot(game).get("modsToRemove").getAsInt());
        assertTrue(events.contains("missing.jar:ABSENT")); assertTrue(events.contains("old.jar:REMOVE_READY"));
        assertEquals("original",Files.readString(game.resolve("mods/old.jar")));
        assertEquals(0,PendingInstall.install(game,false)); assertTrue(PendingInstall.hasPending(game));
        JsonObject entry=PendingInstall.entries(game).getAsJsonObject("old.jar");
        Path backup=StateFiles.root(game).resolve("removed-mods/"+entry.get("backupId").getAsString()+"/old.jar");
        assertEquals(1,PendingInstall.install(game)); assertFalse(Files.exists(game.resolve("mods/old.jar")));
        assertEquals("original",Files.readString(backup)); assertEquals("user mod",Files.readString(game.resolve("mods/user.jar")));
        assertFalse(PendingInstall.hasPending(game)); assertEquals(0,PendingInstall.install(game));
        assertFalse(run().changed()); assertEquals(0,UpdateState.modsToRemove);
    }
    @Test void failedReleaseCannotArmRemovalsAndRetryCan() throws Exception {
        JsonObject bad=server.mod("new.jar","new"); bad.addProperty("sha1","0".repeat(40));
        publish(ConfigFixture.array(bad),new JsonArray(),ConfigFixture.array(removal("old.jar")));
        assertFalse(run().successful()); assertFalse(PendingInstall.hasPending(game));
        assertEquals("original",Files.readString(game.resolve("mods/old.jar"))); assertEquals(0,UpdateState.modsToRemove);
        publish(new JsonArray(),new JsonArray(),ConfigFixture.array(removal("old.jar")));
        assertEquals(1,run().removals());
    }
    @Test void configsWaitForRemovalAndRecoverMissingPendingJournal() throws Exception {
        Files.writeString(game.resolve("config/sample.toml"),"old settings");
        publish(new JsonArray(),ConfigFixture.array(server.file("sample.toml","new settings")),ConfigFixture.array(removal("old.jar")));
        assertTrue(run().successful()); assertEquals("old settings",Files.readString(game.resolve("config/sample.toml")));
        Files.delete(PendingInstall.journal(game));
        assertEquals(0,new ConfigInstaller(game,null).recover(true)); assertTrue(PendingInstall.hasPending(game));
        PendingInstall.install(game); assertEquals(1,new ConfigInstaller(game,null).recover(true));
        assertEquals("new settings",Files.readString(game.resolve("config/sample.toml")));
    }
    @Test void changedRemovalTargetIsPreservedAndBlocksCompletion() throws Exception {
        publish(new JsonArray(),new JsonArray(),ConfigFixture.array(removal("old.jar"))); run();
        Files.writeString(game.resolve("mods/old.jar"),"user changed this");
        assertEquals(0,PendingInstall.install(game)); assertTrue(PendingInstall.hasPending(game));
        assertEquals("user changed this",Files.readString(game.resolve("mods/old.jar")));
    }
    @Test void interruptionAfterMoveBeforeJournalWriteRecoversWithoutLosingBackup() throws Exception {
        publish(new JsonArray(),new JsonArray(),ConfigFixture.array(removal("old.jar"))); run();
        JsonObject entry=PendingInstall.entries(game).getAsJsonObject("old.jar");
        Path backup=StateFiles.root(game).resolve("removed-mods/"+entry.get("backupId").getAsString()+"/old.jar");
        Files.createDirectories(backup.getParent()); Files.move(game.resolve("mods/old.jar"),backup);
        PendingInstall.install(game); assertFalse(PendingInstall.hasPending(game)); assertEquals("original",Files.readString(backup));
    }
    @Test void rejectsUnsafeDuplicateConflictingAndMalformedRemovals() throws Exception {
        for(String name:List.of("../old.jar","sub/old.jar","sub\\old.jar","*.jar","CON.jar","settings.toml")) {
            publish(new JsonArray(),new JsonArray(),ConfigFixture.array(removal(name)));
            assertThrows(IOException.class,()->new PackManifest(server.manifest),name);
        }
        publish(ConfigFixture.array(server.mod("old.jar","new")),new JsonArray(),ConfigFixture.array(removal("OLD.jar")));
        assertFalse(run().successful()); assertEquals(0,server.downloads.get());
        publish(new JsonArray(),new JsonArray(),ConfigFixture.array(removal("old.jar"),removal("OLD.jar")));
        assertThrows(IOException.class,()->new PackManifest(server.manifest));
        for(String value:List.of("null","{}","[\"old.jar\"]","[{\"fileName\":\"old.jar\",\"reason\":null}]")) {
            server.publish(new JsonArray(),new JsonArray());
            JsonObject json=JsonParser.parseString(server.manifest).getAsJsonObject(); json.add("removeMods",JsonParser.parseString(value));
            assertThrows(IOException.class,()->new PackManifest(json.toString()),value);
        }
        assertFalse(PendingInstall.hasPending(game));
    }
    @Test void updaterAndDirectoryTargetsAreProtected() throws Exception {
        SelfUpdateTest.jar(game.resolve("mods/updater.jar"),"2.0");
        publish(new JsonArray(),new JsonArray(),ConfigFixture.array(removal("updater.jar")));
        assertFalse(run().successful()); assertTrue(Files.isRegularFile(game.resolve("mods/updater.jar")));
        Files.createDirectories(game.resolve("mods/folder.jar"));
        publish(new JsonArray(),new JsonArray(),ConfigFixture.array(removal("folder.jar")));
        assertFalse(run().successful()); assertFalse(PendingInstall.hasPending(game));
    }
}
