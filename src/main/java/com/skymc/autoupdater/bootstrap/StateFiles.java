package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Disk protocol across Forge's SERVICE/GAME class loaders; never contains credentials. */
public final class StateFiles {
    public static final String PACK_ID = "mc-mods2-1.20.1";
    public static Path root(Path game) { return game.resolve(".automodupdater/packs").resolve(PACK_ID); }
    public static Path session(Path game, long pid) { return root(game).resolve("session-" + pid + ".json"); }
    public static JsonObject read(Path path) throws IOException {
        try { return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Guncelleme durumu okunamadi: " + path.getFileName(), e); }
    }
    public static void write(Path path, JsonElement data) throws IOException {
        SafePaths.checkLinks(path);
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), "state-", ".tmp");
        try {
            Files.writeString(tmp, data.toString(), StandardCharsets.UTF_8);
            try { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    public static JsonObject snapshot(Path game) {
        JsonObject o = new JsonObject();
        o.addProperty("sessionId", UUID.randomUUID().toString());
        o.addProperty("pid", ProcessHandle.current().pid());
        o.addProperty("gameDir", game.toAbsolutePath().toString());
        o.addProperty("newModsDownloaded", UpdateState.newModsDownloaded);
        o.addProperty("modsToRemove", UpdateState.modsToRemove);
        o.addProperty("configEntriesUpdated", UpdateState.configEntriesUpdated);
        o.addProperty("errorMessage", UpdateState.errorMessage);
        o.addProperty("packId", PACK_ID);
        o.addProperty("autoRestart", Config.autoRestart);
        o.addProperty("helperReady", false);
        JsonArray names = new JsonArray();
        UpdateState.downloadedNames.forEach(names::add);
        o.add("downloadedNames", names);
        JsonArray removals = new JsonArray();
        UpdateState.removalNames.forEach(removals::add); o.add("removalNames", removals);
        return o;
    }
}
