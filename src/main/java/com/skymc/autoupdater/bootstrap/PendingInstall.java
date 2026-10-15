package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** Only completed, hashed, explicitly recorded downloads may enter mods/. */
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
        for (var entry : hashes.entrySet()) {
            String name = entry.getKey(), hash = entry.getValue();
            SafePaths.segment(name);
            if (!validModName(name) || !hash.matches("[a-fA-F0-9]{40}")) throw new IOException("Invalid staged entry");
        }
        locked(game, () -> {
            JsonObject entries = entries(game);
            hashes.forEach(entries::addProperty);
            StateFiles.write(journal(game), entries);
            return 0;
        });
    }
    public static int install(Path game) throws IOException {
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
