package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SelfUpdateTest {
    @TempDir Path temp;
    Path game, installed, next;
    JsonObject publication;
    String originalHash;
    @BeforeEach void setup() throws Exception {
        game = temp.resolve("Ömer profile"); Files.createDirectories(game.resolve("mods"));
        installed = game.resolve("mods/custom-old-name.jar"); jar(installed, "1");
        originalHash = ConfigInstaller.sha256(installed);
        next = temp.resolve("next.jar"); jar(next, "3"); publication = publication(next, "3");
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config/automodupdater.json"), "{\"theme\":\"light\",\"autoRestart\":false,\"custom\":42}");
    }
    static void jar(Path path, String version) throws Exception {
        ByteArrayOutputStream client = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(client)) {
            put(zip, "META-INF/mods.toml", ("modId=\"automodupdater\"\nversion=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
            put(zip, "com/skymc/autoupdater/AutoUpdaterMod.class", new byte[]{1});
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            put(zip, SelfUpdate.BUILD, ("version=" + version + "\nminecraftVersion=1.20.1\nforgeMajor=47\njavaMajor=17\n").getBytes(StandardCharsets.UTF_8));
            put(zip, SelfUpdate.LOCATOR, new byte[]{1});
            put(zip, "com/skymc/autoupdater/bootstrap/InstallerHelper.class", new byte[]{1});
            put(zip, "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator", "com.skymc.autoupdater.bootstrap.UpdaterLocator".getBytes(StandardCharsets.UTF_8));
            put(zip, SelfUpdate.CLIENT, client.toByteArray());
        }
    }
    static void put(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry();
    }
    static JsonObject publication(Path jar, String version) throws Exception {
        JsonObject e = new JsonObject(); e.addProperty("version", version);
        e.addProperty("fileName", "AutoModUpdater-" + version + ".jar");
        e.addProperty("url", "https://example.com/" + version + ".jar");
        e.addProperty("sha256", ConfigInstaller.sha256(jar)); e.addProperty("size", Files.size(jar));
        e.addProperty("minecraftVersion", "1.20.1"); e.addProperty("forgeMajor", 47); e.addProperty("javaMajor", 17); return e;
    }
    SelfUpdate.Prepared prepare() throws Exception {
        return SelfUpdate.prepare(game, publication, UpdateProgress.NONE,
                (url, target, progress) -> Files.copy(next, target));
    }
    void arm() throws Exception { SelfUpdate.arm(game, prepare()); }
    Path target() { return game.resolve("mods/AutoModUpdater-3.jar"); }
    @Test void numericVersionsAreOrderedAndDowngradesNeverDownload() throws Exception {
        assertEquals("2.1", SelfUpdate.version());
        assertTrue(SelfUpdate.compare("10", "2") > 0); assertTrue(SelfUpdate.compare("1.10", "1.9") > 0);
        assertEquals(0, SelfUpdate.compare("1.0", "1"));
        for (String version : List.of("0", "1", "1.0", "2.1")) {
            publication.addProperty("version", version); publication.addProperty("fileName", "AutoModUpdater-" + version + ".jar");
            assertNull(SelfUpdate.prepare(game, publication, UpdateProgress.NONE, (u,t,p) -> fail("No download for older/equal version")));
        }
        for (String invalid : List.of("", "-1", "1-beta", "1..2", "999999999999"))
            assertThrows(IOException.class, () -> SelfUpdate.compare(invalid, "1"));
        assertFalse(SelfUpdate.hasPending(game));
    }
    @Test void completeUpdateKeepsSettingsBacksUpAndLeavesOnlyNewFilename() throws Exception {
        String settings = Files.readString(game.resolve("config/automodupdater.json")); arm();
        assertEquals(originalHash, ConfigInstaller.sha256(installed));
        SelfUpdate.install(game);
        assertFalse(Files.exists(installed)); assertFalse(SelfUpdate.hasPending(game));
        assertEquals(ConfigInstaller.sha256(next), ConfigInstaller.sha256(target()));
        assertEquals(originalHash, ConfigInstaller.sha256(StateFiles.root(game).resolve("self-update/backups/" + originalHash + ".jar")));
        assertEquals(settings, Files.readString(game.resolve("config/automodupdater.json")));
        try (var jars = Files.list(game.resolve("mods"))) { assertEquals(1, jars.filter(p -> p.toString().endsWith(".jar")).count()); }
        SelfUpdate.install(game);
    }
    @Test void cachedVerifiedDownloadIsReusedAfterRetry() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        SelfUpdate.Download fetch = (u,t,p) -> { downloads.incrementAndGet(); Files.copy(next,t); };
        SelfUpdate.prepare(game, publication, UpdateProgress.NONE, fetch);
        SelfUpdate.prepare(game, publication, UpdateProgress.NONE, fetch);
        assertEquals(1, downloads.get()); assertFalse(SelfUpdate.hasPending(game));
    }
    @Test void corruptedAndInterruptedDownloadCannotArmOrReplaceAnything() throws Exception {
        assertThrows(IOException.class, () -> SelfUpdate.prepare(game, publication, UpdateProgress.NONE,
                (u,t,p) -> Files.writeString(t,"corrupt")));
        assertThrows(IOException.class, () -> SelfUpdate.prepare(game, publication, UpdateProgress.NONE,
                (u,t,p) -> { Files.writeString(t,"partial"); throw new IOException("connection lost"); }));
        assertFalse(SelfUpdate.hasPending(game)); assertEquals(originalHash, ConfigInstaller.sha256(installed));
        try (var files = Files.walk(StateFiles.root(game))) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".part"))); }
    }
    @Test void bootstrapOnlyAndWrongEmbeddedVersionCannotBeInstalled() throws Exception {
        Files.writeString(next, "not a full jar"); publication = publication(next,"3");
        assertThrows(IOException.class, this::prepare);
        jar(next,"4"); publication = publication(next,"3"); assertThrows(IOException.class, this::prepare);
        assertFalse(SelfUpdate.hasPending(game)); assertEquals(originalHash,ConfigInstaller.sha256(installed));
    }
    @Test void invalidCompatibilityUnsafeNamesAndInsecureUrlsAreRejected() throws Exception {
        Map<String,Object> invalid = new LinkedHashMap<>(); invalid.put("fileName", "../escape.jar");
        invalid.put("url", "http://example.com/update.jar"); invalid.put("minecraftVersion", "1.21.1");
        invalid.put("forgeMajor", 48); invalid.put("javaMajor", 21); invalid.put("sha256", "bad"); invalid.put("size", Long.MAX_VALUE);
        for (var value : invalid.entrySet()) {
            JsonObject entry = publication.deepCopy(); entry.addProperty(value.getKey(),value.getValue().toString());
            assertThrows(IOException.class, () -> SelfUpdate.validate(entry),value.getKey());
        }
        assertThrows(IOException.class, () -> SelfUpdate.download("http://127.0.0.1:9/update",temp.resolve("part"),(a,b)->{}));
        assertFalse(SelfUpdate.hasPending(game));
    }
    @Test void failedRenameRollsBackAndRetryInstallsCleanly() throws Exception {
        arm();
        assertThrows(IOException.class, () -> SelfUpdate.install(game, (from,to) -> {
            if (to.equals(target())) throw new IOException("Simulated target lock");
            Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);
        }));
        assertEquals(originalHash,ConfigInstaller.sha256(installed)); assertFalse(Files.exists(target())); assertTrue(SelfUpdate.hasPending(game));
        SelfUpdate.install(game); assertFalse(SelfUpdate.hasPending(game)); assertFalse(Files.exists(installed));
    }
    @Test void crashAfterReplacementBeforeRenameResumesFromJournal() throws Exception {
        arm();
        assertThrows(AssertionError.class, () -> SelfUpdate.install(game, (from,to) -> {
            if (to.equals(target())) throw new AssertionError("Simulated abrupt process loss");
            Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);
        }));
        assertTrue(SelfUpdate.hasPending(game)); SelfUpdate.validateJar(installed,"3");
        SelfUpdate.install(game); assertFalse(Files.exists(installed)); assertFalse(SelfUpdate.hasPending(game));
        SelfUpdate.validateJar(target(),"3");
    }
    @Test void crashAfterFinalRenameBeforeJournalCleanupIsIdempotent() throws Exception {
        arm();
        assertThrows(AssertionError.class, () -> SelfUpdate.install(game, (from,to) -> {
            Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);
            if (to.equals(target())) throw new AssertionError("Simulated abrupt process loss");
        }));
        SelfUpdate.install(game); assertFalse(SelfUpdate.hasPending(game)); SelfUpdate.validateJar(target(),"3");
    }
    @Test void changedOriginalCollisionAndTamperedStagingNeverOverwriteUserFiles() throws Exception {
        Files.writeString(target(), "user file"); assertThrows(IOException.class,this::prepare);
        assertEquals("user file",Files.readString(target())); Files.delete(target()); arm();
        Files.writeString(installed,"changed outside updater"); assertThrows(IOException.class,() -> SelfUpdate.install(game));
        assertEquals("changed outside updater",Files.readString(installed)); assertTrue(SelfUpdate.hasPending(game));
        jar(installed,"1");
        // ZIP timestamps differ between encodings; restore the journal's exact original from a saved copy instead.
        JsonObject journal = StateFiles.read(SelfUpdate.journal(game)); journal.addProperty("oldSha256",ConfigInstaller.sha256(installed));
        StateFiles.write(SelfUpdate.journal(game),journal);
        Files.writeString(StateFiles.root(game).resolve("self-update/"+publication.get("sha256").getAsString()+".jar"),"tampered");
        assertThrows(IOException.class,() -> SelfUpdate.install(game)); assertTrue(Files.exists(installed));
    }
    @Test void ordinaryModChannelCannotOverwriteUpdaterAndDuplicateInstallIsRejected() throws Exception {
        JsonObject mod = new JsonObject(); mod.addProperty("fileName",installed.getFileName().toString());
        assertThrows(IOException.class,() -> SelfUpdate.protectModTargets(game,ConfigFixture.array(mod)));
        Files.copy(installed,game.resolve("mods/duplicate.jar")); assertThrows(IOException.class,this::prepare);
    }
    @Test void pathTamperingInJournalIsRejected() throws Exception {
        arm(); JsonObject journal = StateFiles.read(SelfUpdate.journal(game)); journal.addProperty("oldName","../outside.jar");
        StateFiles.write(SelfUpdate.journal(game),journal);
        assertThrows(IOException.class,() -> SelfUpdate.install(game)); assertEquals(originalHash,ConfigInstaller.sha256(installed));
    }
}
