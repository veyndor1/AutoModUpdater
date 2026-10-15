package com.skymc.autoupdater;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class ClientState {
    final JsonObject json;
    final Path root;
    final long pid;
    static boolean promptDismissed;
    private ClientState(JsonObject json, Path root, long pid) {
        this.json = json; this.root = root; this.pid = pid;
    }
    static ClientState load() {
        Path root = Minecraft.getInstance().gameDirectory.toPath().resolve(".automodupdater/packs/mc-mods2-1.20.1");
        long pid = ProcessHandle.current().pid();
        try {
            JsonObject data = JsonParser.parseString(Files.readString(root.resolve("session-" + pid + ".json"))).getAsJsonObject();
            if (data.get("pid").getAsLong() != pid) return null;
            return new ClientState(data, root, pid);
        } catch (Exception e) { return null; }
    }
    int mods() { return json.get("newModsDownloaded").getAsInt(); }
    String error() { return json.has("errorMessage") ? json.get("errorMessage").getAsString() : ""; }
    boolean helperReady() { return json.has("helperReady") && json.get("helperReady").getAsBoolean(); }
    boolean autoRestart() { return json.get("autoRestart").getAsBoolean(); }
    boolean canRelaunch() { return json.has("canRelaunch") && json.get("canRelaunch").getAsBoolean(); }
    List<String> names() {
        List<String> names = new ArrayList<>();
        json.getAsJsonArray("downloadedNames").forEach(e -> names.add(e.getAsString()));
        return names;
    }
    boolean request(String action, boolean relaunch) {
        JsonObject request = new JsonObject();
        request.add("sessionId", json.get("sessionId"));
        request.addProperty("relaunch", relaunch);
        Path target = root.resolve((action.equals("install") ? "install-" : "request-") + pid + ".json");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, "request-", ".tmp");
            Files.writeString(temporary, request.toString(), StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException e) {
            System.out.println("[AutoModUpdater] Kurulum istegi yazilamadi: " + e.getClass().getSimpleName());
            return false;
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
