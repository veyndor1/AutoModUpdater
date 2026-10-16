package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Verified config snapshots, exact-tree comparison and recoverable batch replacement. */
final class ConfigInstaller {
    interface Move { void move(Path from, Path to) throws IOException; }
    interface Space { long usable(Path path) throws IOException; }
    record Prepared(String id, JsonArray entries) {}
    private final Path game, root, config, journal;
    private final UpdateProgress window;
    private final Move move;
    private final Space space;
    ConfigInstaller(Path game, UpdateProgress window) throws IOException {
        this(game, window, (a, b) -> Files.move(a, b));
    }
    ConfigInstaller(Path game, UpdateProgress window, Move move) throws IOException {
        this(game, window, move, path -> Files.getFileStore(path).getUsableSpace());
    }
    ConfigInstaller(Path game, UpdateProgress window, Move move, Space space) throws IOException {
        this.game = game.toAbsolutePath().normalize(); this.root = StateFiles.root(this.game);
        this.config = this.game.resolve("config"); this.journal = root.resolve("config-transaction.json");
        this.window = window; this.move = move; this.space = space;
        SafePaths.checkLinks(root); SafePaths.checkLinks(config);
        Files.createDirectories(root);
    }
    private void status(String value) { if (window != null) window.status(value); }
    private void progress(JsonObject entry, UpdateProgress.Phase phase, long bytes, long total) {
        if (window != null) window.item(UpdateProgress.Kind.CONFIG, entry.get("path").getAsString(),
                entry.get("path").getAsString(), phase, bytes, total);
    }
    static String sha256(Path path) throws IOException { return digest(path, "SHA-256"); }
    static String digest(Path path, String algorithm) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) >= 0) md.update(buffer, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    static void validateInventory(JsonObject entry) throws IOException {
        if (entry.get("size").getAsLong() < 0) throw new IOException("Invalid config package size");
        if (entry.get("type").getAsString().equals("file")) {
            if (entry.get("size").getAsLong() < 0) throw new IOException("Invalid file size");
            return;
        }
        Map<String, String> kinds = new HashMap<>();
        JsonArray dirs = entry.getAsJsonArray("directories"), files = entry.getAsJsonArray("files");
        if (dirs == null || files == null) throw new IOException("Missing config inventory");
        for (JsonElement dir : dirs) inventoryName(kinds, dir.getAsString(), "directory");
        for (JsonElement file : files) {
            JsonObject f = file.getAsJsonObject(); inventoryName(kinds, f.get("path").getAsString(), "file");
            if (!f.get("sha256").getAsString().matches("(?i)[a-f0-9]{64}") || f.get("size").getAsLong() < 0)
                throw new IOException("Invalid config file inventory");
        }
        for (String name : kinds.keySet()) {
            int slash = name.lastIndexOf('/');
            if (slash >= 0 && !"directory".equals(kinds.get(name.substring(0, slash))))
                throw new IOException("Missing parent directory");
        }
    }
    private static void inventoryName(Map<String,String> kinds, String name, String kind) throws IOException {
        SafePaths.relative(name);
        if (kinds.putIfAbsent(name.toLowerCase(Locale.ROOT), kind) != null) throw new IOException("Duplicate config path");
    }
    static boolean matches(Path target, JsonObject entry) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
        SafePaths.checkLinks(target);
        if (entry.get("type").getAsString().equals("file"))
            return Files.isRegularFile(target) && Files.size(target) == entry.get("size").getAsLong()
                    && sha256(target).equalsIgnoreCase(entry.get("sha256").getAsString());
        if (!Files.isDirectory(target)) return false;
        Map<String, JsonObject> files = new HashMap<>(); Set<String> dirs = new HashSet<>();
        entry.getAsJsonArray("files").forEach(f -> files.put(f.getAsJsonObject().get("path").getAsString(), f.getAsJsonObject()));
        entry.getAsJsonArray("directories").forEach(d -> dirs.add(d.getAsString()));
        boolean same = true; Set<String> foundFiles = new HashSet<>(), foundDirs = new HashSet<>();
        try (var paths = Files.walk(target)) {
            for (Path path : paths.toList()) {
                if (path.equals(target)) continue;
                String relative = target.relativize(path).toString().replace('\\', '/');
                SafePaths.resolve(target, relative);
                if (Files.isDirectory(path)) { foundDirs.add(relative); if (!dirs.contains(relative)) same = false; }
                else if (Files.isRegularFile(path)) {
                    foundFiles.add(relative); JsonObject expected = files.get(relative);
                    if (expected == null || Files.size(path) != expected.get("size").getAsLong()
                            || !sha256(path).equalsIgnoreCase(expected.get("sha256").getAsString())) same = false;
                } else throw new IOException("Unsupported config filesystem entry");
            }
        }
        return same && foundFiles.equals(files.keySet()) && foundDirs.equals(dirs);
    }
    Prepared prepare(JsonArray entries) throws IOException {
        if (Files.exists(journal)) throw new IOException("Unfinished config transaction");
        String id = UUID.randomUUID().toString(); JsonArray changed = new JsonArray();
        Path stage = stage(id);
        try {
            for (JsonElement value : entries) {
                JsonObject entry = value.getAsJsonObject();
                Path target = target(entry);
                status("Config kontrol ediliyor: " + entry.get("path").getAsString());
                progress(entry, UpdateProgress.Phase.CHECKING, 0, -1);
                if (matches(target, entry)) {
                    progress(entry, UpdateProgress.Phase.CURRENT, 0, -1); continue;
                }
                String hash = entry.get("sha256").getAsString().toLowerCase(Locale.ROOT);
                Path cache = SafePaths.resolve(root, "config-cache/" + hash);
                Files.createDirectories(cache.getParent());
                Path destination = SafePaths.resolve(stage, entry.get("path").getAsString());
                Files.createDirectories(destination.getParent());
                boolean cached = Files.isRegularFile(cache) && Files.size(cache) == entry.get("size").getAsLong() && sha256(cache).equals(hash);
                long expanded = entry.get("type").getAsString().equals("file") ? entry.get("size").getAsLong() : totalSize(entry);
                long required;
                try { required = Math.addExact(expanded, cached ? 0 : entry.get("size").getAsLong()); }
                catch (ArithmeticException invalid) { throw new IOException("Config size overflow", invalid); }
                if (space.usable(destination.getParent()) < required) throw new IOException("Insufficient config staging space");
                if (!cached) {
                    status("Config indiriliyor: " + entry.get("path").getAsString());
                    Path part = SafePaths.resolve(root, "config-cache/" + hash + ".part");
                    try {
                        progress(entry, UpdateProgress.Phase.DOWNLOADING, 0, entry.get("size").getAsLong());
                        ModDownloader.httpDownload(entry.get("url").getAsString(), part.toFile(), entry.get("size").getAsLong(),
                                (bytes, total) -> progress(entry, UpdateProgress.Phase.DOWNLOADING, bytes, total));
                        progress(entry, UpdateProgress.Phase.VERIFYING, entry.get("size").getAsLong(), entry.get("size").getAsLong());
                        if (Files.size(part) != entry.get("size").getAsLong() || !sha256(part).equals(hash)) throw new IOException("Config SHA256/size mismatch");
                        Files.move(part, cache, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(part); }
                }
                status("Config dogrulaniyor: " + entry.get("path").getAsString());
                progress(entry, UpdateProgress.Phase.VERIFYING, 0, -1);
                if (entry.get("type").getAsString().equals("file")) Files.copy(cache, destination);
                else extract(cache, destination, entry);
                if (!matches(destination, entry)) throw new IOException("Config inventory mismatch");
                JsonObject item = entry.deepCopy(); item.addProperty("state", "prepared"); changed.add(item);
                progress(entry, UpdateProgress.Phase.READY, 0, -1);
            }
            return new Prepared(id, changed);
        } catch (RuntimeException | IOException e) {
            if (Files.exists(stage.getParent())) SafePaths.deleteOwnedTree(root, stage.getParent());
            throw new IOException("Config preparation failed", e);
        }
    }
    private static long totalSize(JsonObject entry) throws IOException {
        long result = 0;
        try { for (JsonElement file : entry.getAsJsonArray("files")) result = Math.addExact(result, file.getAsJsonObject().get("size").getAsLong()); }
        catch (ArithmeticException e) { throw new IOException("Config size overflow", e); }
        return result;
    }
    private static void extract(Path archive, Path destination, JsonObject entry) throws IOException {
        Files.createDirectories(destination);
        Map<String, JsonObject> files = new HashMap<>(); Set<String> dirs = new HashSet<>(), seen = new HashSet<>();
        entry.getAsJsonArray("files").forEach(f -> files.put(f.getAsJsonObject().get("path").getAsString(), f.getAsJsonObject()));
        entry.getAsJsonArray("directories").forEach(d -> dirs.add(d.getAsString()));
        // Empty directories are explicit inventory entries even if the ZIP omits their records.
        for (String dir : dirs) Files.createDirectories(SafePaths.resolve(destination, dir));
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (ZipEntry z = zip.getNextEntry(); z != null; z = zip.getNextEntry()) {
                String name = z.getName(); if (z.isDirectory()) name = name.substring(0, name.length() - 1);
                SafePaths.relative(name);
                if (!seen.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate ZIP path");
                Path path = SafePaths.resolve(destination, name);
                if (z.isDirectory()) {
                    if (!dirs.contains(name)) throw new IOException("Unexpected ZIP directory");
                } else {
                    JsonObject expected = files.get(name);
                    if (expected == null) throw new IOException("Unexpected ZIP file");
                    long size = expected.get("size").getAsLong(), written = 0;
                    Files.createDirectories(path.getParent());
                    try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer = new byte[65536]; int n;
                        while ((n = zip.read(buffer)) >= 0) {
                            written += n; if (written > size) throw new IOException("ZIP file exceeds declared size");
                            out.write(buffer, 0, n);
                        }
                    }
                    if (written != size || !sha256(path).equalsIgnoreCase(expected.get("sha256").getAsString()))
                        throw new IOException("ZIP file hash/size mismatch");
                }
            }
        }
    }
    void arm(Prepared prepared, Map<String,String> requiredMods) throws IOException {
        arm(prepared, requiredMods, Map.of());
    }
    void arm(Prepared prepared, Map<String,String> requiredMods, Map<String,String> requiredRemovals) throws IOException {
        if (prepared.entries.size() == 0) return;
        JsonObject tx = new JsonObject(); tx.addProperty("id", prepared.id); tx.addProperty("packId", StateFiles.PACK_ID);
        tx.addProperty("phase", "prepared"); tx.addProperty("deferred", !requiredMods.isEmpty() || !requiredRemovals.isEmpty()); tx.add("entries", prepared.entries);
        JsonObject mods = new JsonObject(); requiredMods.forEach(mods::addProperty); tx.add("requiredMods", mods);
        JsonObject removals = new JsonObject(); requiredRemovals.forEach(removals::addProperty); tx.add("requiredRemovals", removals);
        StateFiles.write(journal, tx);
    }
    int recover(boolean allowDeferred) throws IOException {
        if (!Files.exists(journal)) return 0;
        JsonObject tx = readTransaction(); String phase = tx.get("phase").getAsString();
        if (phase.equals("committed")) { finish(tx); return 0; }
        if (!phase.equals("prepared")) { rollback(tx); return 0; }
        if (tx.get("deferred").getAsBoolean()) {
            if (!allowDeferred) return 0;
            Map<String,String> missing = new LinkedHashMap<>();
            for (var mod : tx.getAsJsonObject("requiredMods").entrySet()) {
                Path target = SafePaths.resolve(game.resolve("mods"), mod.getKey());
                if (!Files.isRegularFile(target) || !ModDownloader.sha1Of(target.toFile()).equalsIgnoreCase(mod.getValue().getAsString()))
                    missing.put(mod.getKey(), mod.getValue().getAsString());
            }
            // The config journal is written before the mod journal; recover a crash between them.
            Map<String,String> removals = new LinkedHashMap<>();
            if (tx.has("requiredRemovals")) for (var entry : tx.getAsJsonObject("requiredRemovals").entrySet()) {
                if (Files.exists(SafePaths.resolve(game.resolve("mods"), entry.getKey()), LinkOption.NOFOLLOW_LINKS))
                    removals.put(entry.getKey(), entry.getValue().getAsString());
            }
            if (!missing.isEmpty() || !removals.isEmpty()) PendingInstall.recordBatch(game, missing, removals);
            if (PendingInstall.hasPending(game)) return 0;
        }
        return commit(tx);
    }
    private JsonObject readTransaction() throws IOException {
        JsonObject tx = StateFiles.read(journal);
        try {
            if (!StateFiles.PACK_ID.equals(tx.get("packId").getAsString())) throw new IOException("Wrong config transaction pack");
            UUID.fromString(tx.get("id").getAsString());
            if (!tx.get("id").getAsString().matches("[a-f0-9-]{36}")) throw new IOException("Invalid transaction id");
            if (!Set.of("prepared", "applying", "committed", "rolling-back").contains(tx.get("phase").getAsString()))
                throw new IOException("Invalid config transaction phase");
            JsonObject publication = new JsonObject(); publication.addProperty("schemaVersion", 2);
            publication.addProperty("minecraftVersion", "1.20.1"); publication.addProperty("packId", StateFiles.PACK_ID);
            publication.add("mods", new JsonArray()); publication.add("configs", tx.getAsJsonArray("entries"));
            new PackManifest(publication.toString());
            for (var mod : tx.getAsJsonObject("requiredMods").entrySet()) {
                SafePaths.segment(mod.getKey());
                if (!PendingInstall.validModName(mod.getKey()) || !mod.getValue().getAsString().matches("(?i)[a-f0-9]{40}"))
                    throw new IOException("Invalid config/mod dependency");
            }
            if (tx.has("requiredRemovals")) for (var entry : tx.getAsJsonObject("requiredRemovals").entrySet()) {
                SafePaths.segment(entry.getKey());
                if (!PendingInstall.validModName(entry.getKey()) || !entry.getValue().getAsString().matches("(?i)[a-f0-9]{40}"))
                    throw new IOException("Invalid config/removal dependency");
            }
            Set<String> names = new HashSet<>();
            for (JsonElement item : tx.getAsJsonArray("entries")) {
                JsonObject entry = item.getAsJsonObject(); target(entry); validateInventory(entry);
                if (!Set.of("prepared", "backup-intent", "backed-up", "install-intent", "installed", "restore-intent", "remove-intent", "restored")
                        .contains(entry.get("state").getAsString())) throw new IOException("Invalid config entry state");
                if (!names.add(entry.get("path").getAsString().toLowerCase(Locale.ROOT))) throw new IOException("Duplicate transaction target");
            }
            return tx;
        } catch (RuntimeException e) { throw new IOException("Invalid config recovery journal", e); }
    }
    private int commit(JsonObject tx) throws IOException {
        String id = tx.get("id").getAsString();
        try {
            for (JsonElement value : tx.getAsJsonArray("entries")) {
                JsonObject item = value.getAsJsonObject(); Path dest = target(item);
                if (!matches(SafePaths.resolve(stage(id), item.get("path").getAsString()), item)) throw new IOException("Staged config was modified");
                if (Files.exists(dest)) {
                    // Check every existing descendant before retaining the complete original tree.
                    if (Files.isDirectory(dest)) try (var paths = Files.walk(dest)) {
                        for (Path path : paths.toList()) SafePaths.checkLinks(path);
                    }
                }
                item.addProperty("hadOriginal", Files.exists(dest));
            }
            tx.addProperty("phase", "applying"); StateFiles.write(journal, tx);
            for (JsonElement value : tx.getAsJsonArray("entries")) {
                JsonObject item = value.getAsJsonObject(); String name = item.get("path").getAsString();
                Path dest = target(item), backup = backup(id, name);
                status("Config yedekleniyor: " + name); state(tx, item, "backup-intent");
                if (item.get("hadOriginal").getAsBoolean()) {
                    Files.createDirectories(backup.getParent()); move.move(dest, backup);
                }
                state(tx, item, "backed-up"); status("Config kuruluyor: " + name);
                state(tx, item, "install-intent"); Files.createDirectories(dest.getParent());
                move.move(SafePaths.resolve(stage(id), name), dest); state(tx, item, "installed");
            }
            tx.addProperty("phase", "committed"); StateFiles.write(journal, tx);
        } catch (IOException | RuntimeException failure) {
            try { rollback(tx); } catch (IOException rollback) {
                failure.addSuppressed(rollback); throw new RecoveryException("Config rollback could not complete", failure);
            }
            throw new IOException("Config update rolled back", failure);
        }
        int count = tx.getAsJsonArray("entries").size(); finish(tx); return count;
    }
    private void rollback(JsonObject tx) throws IOException {
        String id = tx.get("id").getAsString(); tx.addProperty("phase", "rolling-back"); StateFiles.write(journal, tx);
        JsonArray entries = tx.getAsJsonArray("entries");
        for (int i = entries.size() - 1; i >= 0; i--) {
            JsonObject item = entries.get(i).getAsJsonObject(); String state = item.get("state").getAsString();
            if (state.equals("prepared") || state.equals("restored")) continue;
            String name = item.get("path").getAsString(); Path dest = target(item), backup = backup(id, name);
            if (Files.exists(backup)) {
                state(tx, item, "restore-intent");
                retainFailedNew(id, name, dest); Files.createDirectories(dest.getParent()); move.move(backup, dest);
            } else if (item.get("hadOriginal").getAsBoolean()) {
                if (!Files.exists(dest) || (!state.equals("restore-intent") && !Files.exists(SafePaths.resolve(stage(id), name))))
                    throw new RecoveryException("Original config could not be restored: " + name, null);
            } else if (!Files.exists(SafePaths.resolve(stage(id), name))) {
                state(tx, item, "remove-intent"); retainFailedNew(id, name, dest);
            }
            state(tx, item, "restored");
        }
        Files.delete(journal);
    }
    private void retainFailedNew(String id, String name, Path dest) throws IOException {
        if (!Files.exists(dest)) return;
        Path retained = SafePaths.resolve(root, "config-staging/" + id + "/rollback-new/" + name);
        if (Files.exists(retained)) throw new IOException("Recovery destination already exists");
        Files.createDirectories(retained.getParent()); move.move(dest, retained);
    }
    private void finish(JsonObject tx) throws IOException {
        String id = tx.get("id").getAsString();
        StateFiles.write(SafePaths.resolve(root, "config-results/" + id + ".json"), tx);
        Files.deleteIfExists(journal);
        Path stageRoot = stage(id).getParent();
        if (Files.exists(stageRoot)) SafePaths.deleteOwnedTree(root, stageRoot);
    }
    private void state(JsonObject tx, JsonObject item, String value) throws IOException {
        item.addProperty("state", value); StateFiles.write(journal, tx);
    }
    private Path stage(String id) throws IOException { return SafePaths.resolve(root, "config-staging/" + id + "/entries"); }
    private Path backup(String id, String name) throws IOException { return SafePaths.resolve(root, "config-backups/" + id + "/" + name); }
    private Path target(JsonObject item) throws IOException {
        String name = item.get("path").getAsString(); SafePaths.segment(name);
        if (name.equalsIgnoreCase("automodupdater.json")) throw new IOException("Updater settings are protected");
        return SafePaths.resolve(config, name);
    }
    static final class RecoveryException extends IOException {
        RecoveryException(String message, Throwable cause) { super(message, cause); }
    }
}
