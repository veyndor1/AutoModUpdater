package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/** Verified downloads and exact, hashed removals share the recoverable install journal. */
public final class PendingInstall {
    static Path journal(Path game) { return StateFiles.root(game).resolve("pending.json"); }
    public static boolean validModName(String name) {
        return name != null && !name.isBlank() && !name.contains("/") && !name.contains("\\")
                && !name.contains(":") && name.toLowerCase(Locale.ROOT).endsWith(".jar");
    }
    static JsonObject entries(Path game) throws IOException {
        return Files.exists(journal(game)) ? StateFiles.read(journal(game)) : new JsonObject();
    }
    public static boolean hasPending(Path game) throws IOException { return entries(game).size() > 0; }
    public static void record(Path game, String name, String hash) throws IOException {
        recordBatch(game, Map.of(name, hash));
    }
    public static void recordBatch(Path game, Map<String,String> hashes) throws IOException {
        recordBatch(game, hashes, Map.of());
    }
    static void recordBatch(Path game, Map<String,String> hashes, Map<String,String> removals) throws IOException {
        for (var entry : hashes.entrySet()) {
            String name = entry.getKey(), hash = entry.getValue();
            SafePaths.segment(name);
            if (!validModName(name) || !hash.matches("[a-fA-F0-9]{40}")) throw new IOException("Invalid staged entry");
        }
        for (var entry : removals.entrySet()) {
            SafePaths.segment(entry.getKey());
            if (!validModName(entry.getKey()) || !entry.getValue().matches("[a-fA-F0-9]{40}")
                    || hashes.keySet().stream().anyMatch(n -> n.equalsIgnoreCase(entry.getKey())))
                throw new IOException("Invalid removal entry");
        }
        locked(game, () -> {
            JsonObject entries = entries(game);
            hashes.forEach(entries::addProperty);
            for (var removal : removals.entrySet()) {
                if (entries.has(removal.getKey())) {
                    JsonElement old = entries.get(removal.getKey());
                    if (!old.isJsonObject() || !old.getAsJsonObject().get("sha1").getAsString().equalsIgnoreCase(removal.getValue()))
                        throw new IOException("Conflicting pending mod operation");
                    continue;
                }
                JsonObject entry = new JsonObject(); entry.addProperty("sha1", removal.getValue());
                entry.addProperty("backupId", UUID.randomUUID().toString()); entries.add(removal.getKey(), entry);
            }
            StateFiles.write(journal(game), entries);
            return 0;
        });
    }
    public static int install(Path game) throws IOException {
        return install(game, true);
    }
    static int install(Path game, boolean allowRemovals) throws IOException {
        return locked(game, () -> {
            JsonObject entries = entries(game);
            Path staging = StateFiles.root(game).resolve("staging");
            Path mods = game.resolve("mods");
            SafePaths.checkLinks(staging); SafePaths.checkLinks(mods);
            Files.createDirectories(mods);
            int moved = 0;
            Iterator<Map.Entry<String, JsonElement>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonElement> entry = it.next();
                String name = entry.getKey();
                if (entry.getValue().isJsonObject()) {
                    if (!allowRemovals) continue; // Removal must wait until the parent JVM has exited.
                    SafePaths.segment(name);
                    JsonObject removal = entry.getValue().getAsJsonObject();
                    String hash = removal.get("sha1").getAsString(), id = removal.get("backupId").getAsString();
                    if (!validModName(name) || !hash.matches("(?i)[a-f0-9]{40}") || !id.matches("[a-f0-9-]{36}"))
                        throw new IOException("Invalid removal journal");
                    UUID.fromString(id);
                    Path target = SafePaths.resolve(mods, name);
                    try {
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            if (!Files.isRegularFile(target) || SelfUpdate.isDistribution(target)
                                    || !ModDownloader.sha1Of(target.toFile()).equalsIgnoreCase(hash))
                                throw new IOException("Removal target changed: " + name);
                            Path backup = SafePaths.resolve(StateFiles.root(game), "removed-mods/" + id + "/" + name);
                            Files.createDirectories(backup.getParent());
                            Files.move(target, backup);
                            moved++;
                        }
                        it.remove();
                    } catch (IOException blocked) {
                        System.out.println("[AutoModUpdater] Kaldirma bekliyor (" + name + "): " + blocked.getClass().getSimpleName());
                    }
                    continue;
                }
                if (!validModName(name) || !entry.getValue().isJsonPrimitive()) continue;
                String hash = entry.getValue().getAsString();
                if (!hash.matches("[a-fA-F0-9]{40}")) continue;
                Path source = staging.resolve(name), target = mods.resolve(name);
                // Reject symlink/junction escape at installation, as well as during download.
                if (!under(staging, source) || !under(mods, target)) continue;
                try {
                    if (Files.isRegularFile(target) && ModDownloader.sha1Of(target.toFile()).equalsIgnoreCase(hash)) {
                        if (Files.exists(source)) Files.delete(source);
                        it.remove();
                    } else if (Files.isRegularFile(source) && ModDownloader.sha1Of(source.toFile()).equalsIgnoreCase(hash)) {
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                        it.remove();
                        moved++;
                    }
                } catch (IOException e) {
                    System.out.println("[AutoModUpdater] Kurulum bekliyor (" + name + "): " + e.getClass().getSimpleName());
                }
            }
            StateFiles.write(journal(game), entries);
            return moved;
        });
    }
    private static boolean under(Path root, Path target) throws IOException {
        return target.toFile().getCanonicalFile().toPath().startsWith(root.toFile().getCanonicalFile().toPath())
                && !target.toFile().getCanonicalFile().equals(root.toFile().getCanonicalFile());
    }
    private interface IOAction { int run() throws IOException; }
    private static synchronized int locked(Path game, IOAction action) throws IOException {
        SafePaths.checkLinks(StateFiles.root(game));
        Files.createDirectories(StateFiles.root(game));
        try (FileChannel channel = FileChannel.open(StateFiles.root(game).resolve("pending.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) { return action.run(); }
    }
}
