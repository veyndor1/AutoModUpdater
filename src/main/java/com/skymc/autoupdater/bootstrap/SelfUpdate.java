package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.*;

/** A separate journal keeps the updater out of the ordinary live mod installer. */
final class SelfUpdate {
    static final String BUILD = "updater/build.properties";
    static final String CLIENT = "embedded/automodupdater-client.jar";
    static final String LOCATOR = "com/skymc/autoupdater/bootstrap/UpdaterLocator.class";
    static final long MAX_JAR = 128L * 1024 * 1024;
    record Prepared(JsonObject journal, String name) {}
    interface Download { void get(String url, Path target, ModDownloader.Transfer progress) throws IOException; }
    interface Move { void move(Path from, Path to) throws IOException; }

    static String version() throws IOException {
        try (InputStream in = SelfUpdate.class.getResourceAsStream("/" + BUILD)) {
            if (in == null) throw new IOException("Missing updater build version");
            return properties(in).getProperty("version");
        }
    }
    private static Properties properties(InputStream in) throws IOException {
        Properties values = new Properties(); values.load(in); return values;
    }
    static int compare(String left, String right) throws IOException {
        if (left == null || right == null || !left.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,3}")
                || !right.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,3}")) throw new IOException("Invalid updater version");
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int result = new BigInteger(i < a.length ? a[i] : "0").compareTo(new BigInteger(i < b.length ? b[i] : "0"));
            if (result != 0) return result;
        }
        return 0;
    }
    static void validate(JsonObject entry) throws IOException {
        try {
            String version = entry.get("version").getAsString(); compare(version, version);
            String name = entry.get("fileName").getAsString(); SafePaths.segment(name);
            if (!name.equals("AutoModUpdater-" + version + ".jar")
                    || !entry.get("sha256").getAsString().matches("(?i)[a-f0-9]{64}")
                    || entry.get("size").getAsLong() <= 0 || entry.get("size").getAsLong() > MAX_JAR
                    || !entry.get("minecraftVersion").getAsString().equals("1.20.1")
                    || !entry.get("forgeMajor").getAsString().equals("47")
                    || !entry.get("javaMajor").getAsString().equals("17")) throw new IOException("Incompatible updater publication");
            https(entry.get("url").getAsString());
        } catch (RuntimeException e) { throw new IOException("Invalid updater publication", e); }
    }
    private static URL https(String address) throws IOException {
        URL url = new URL(address);
        if (!url.getProtocol().equals("https") || url.getHost().isBlank() || url.getUserInfo() != null
                || url.getRef() != null) throw new IOException("Updater downloads require HTTPS");
        return url;
    }
    static void download(String address, Path target, ModDownloader.Transfer progress) throws IOException {
        URL url = https(address);
        for (int redirects = 0; redirects <= 6; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "AutoModUpdater/" + version());
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing updater HTTP redirect");
                    url = https(new URL(url, location).toString()); continue;
                }
                if (code != 200) throw new IOException("Updater HTTP " + code);
                long total = connection.getContentLengthLong(), count = 0;
                if (total > MAX_JAR) throw new IOException("Updater JAR too large");
                try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        count += n;
                        if (count > MAX_JAR) throw new IOException("Updater JAR too large");
                        out.write(buffer, 0, n); progress.update(count, total);
                    }
                    if (total >= 0 && count != total) throw new IOException("Truncated updater JAR");
                }
                return;
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many updater HTTP redirects");
    }
    static boolean isDistribution(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return false;
        try (JarFile jar = new JarFile(path.toFile())) {
            return jar.getJarEntry(LOCATOR) != null && jar.getJarEntry(CLIENT) != null;
        } catch (ZipException ignored) { return false; }
    }
    static Path distribution(Path game) throws IOException {
        Path mods = game.resolve("mods"); SafePaths.checkLinks(mods);
        Path found = null;
        if (!Files.isDirectory(mods)) return null;
        try (var files = Files.list(mods)) {
            for (Path path : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                SafePaths.checkLinks(path);
                if (isDistribution(path)) {
                    if (found != null) throw new IOException("Duplicate Auto Mod Updater JARs");
                    found = path;
                }
            }
        }
        return found;
    }
    static void protectModTargets(Path game, JsonArray mods) throws IOException {
        Path installed = distribution(game);
        for (JsonElement value : mods) {
            String name = value.getAsJsonObject().get("fileName").getAsString();
            if (installed != null && name.equalsIgnoreCase(installed.getFileName().toString()))
                throw new IOException("Updater JAR must use the updater publication section");
        }
    }
    static Prepared prepare(Path game, JsonObject entry, UpdateProgress window) throws IOException {
        return prepare(game, entry, window, SelfUpdate::download);
    }
    static Prepared prepare(Path game, JsonObject entry, UpdateProgress window, Download download) throws IOException {
        if (entry == null) return null;
        validate(entry);
        String next = entry.get("version").getAsString();
        if (compare(next, version()) <= 0) return null;
        Path installed = distribution(game);
        if (installed == null) throw new IOException("Installed updater JAR not found");
        String name = entry.get("fileName").getAsString(), display = "Auto Mod Updater " + next;
        window.inventory(UpdateProgress.Kind.MOD, name, display);
        Path dir = SafePaths.resolve(StateFiles.root(game), "self-update"); Files.createDirectories(dir);
        String hash = entry.get("sha256").getAsString().toLowerCase(Locale.ROOT);
        Path staged = SafePaths.resolve(dir, hash + ".jar");
        window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.CHECKING, 0, -1);
        try {
            if (!matches(staged, hash) || Files.size(staged) != entry.get("size").getAsLong()) {
                Path part = SafePaths.resolve(dir, hash + ".part");
                try {
                    if (Files.getFileStore(dir).getUsableSpace() < entry.get("size").getAsLong() + Files.size(installed))
                        throw new IOException("Insufficient updater staging space");
                    window.status("Auto Mod Updater " + next + " indiriliyor");
                    window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.DOWNLOADING, 0, entry.get("size").getAsLong());
                    download.get(entry.get("url").getAsString(), part,
                            (bytes, total) -> window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.DOWNLOADING, bytes, total));
                    if (Files.size(part) != entry.get("size").getAsLong() || !matches(part, hash)) throw new IOException("Updater SHA256/size mismatch");
                    validateJar(part, next);
                    Files.move(part, staged, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(part); }
            }
            validateJar(staged, next);
            Path target = SafePaths.resolve(game.resolve("mods"), name);
            if (!target.equals(installed) && Files.exists(target)) throw new IOException("Updater target already exists");
            JsonObject journal = entry.deepCopy();
            journal.addProperty("oldName", installed.getFileName().toString());
            journal.addProperty("oldSha256", ConfigInstaller.sha256(installed));
            window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.READY, 0, -1);
            return new Prepared(journal, display);
        } catch (IOException error) {
            window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.FAILED, 0, -1); throw error;
        }
    }
    static void validateJar(Path path, String version) throws IOException {
        if (Files.size(path) > MAX_JAR) throw new IOException("Updater JAR too large");
        try (JarFile jar = new JarFile(path.toFile())) {
            for (String required : List.of(BUILD, LOCATOR, CLIENT,
                    "com/skymc/autoupdater/bootstrap/InstallerHelper.class",
                    "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator"))
                if (jar.getJarEntry(required) == null) throw new IOException("Incomplete updater JAR");
            Properties build;
            try (InputStream in = jar.getInputStream(jar.getJarEntry(BUILD))) { build = properties(new ByteArrayInputStream(limited(in, 4096))); }
            if (!version.equals(build.getProperty("version")) || !"1.20.1".equals(build.getProperty("minecraftVersion"))
                    || !"47".equals(build.getProperty("forgeMajor")) || !"17".equals(build.getProperty("javaMajor")))
                throw new IOException("Updater JAR version/compatibility mismatch");
            byte[] client;
            try (InputStream in = jar.getInputStream(jar.getJarEntry(CLIENT))) { client = limited(in, (int) MAX_JAR); }
            boolean metadata = false, entrypoint = false; long expanded = 0;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(client))) {
                for (var item = zip.getNextEntry(); item != null; item = zip.getNextEntry()) {
                    byte[] bytes = limited(zip, 8 * 1024 * 1024); expanded += bytes.length;
                    if (expanded > MAX_JAR) throw new IOException("Embedded updater too large");
                    if (item.getName().equals("META-INF/mods.toml")) {
                        String text = new String(bytes, StandardCharsets.UTF_8);
                        metadata = text.matches("(?s).*\\bmodId\\s*=\\s*\"automodupdater\".*")
                                && text.matches("(?s).*\\bversion\\s*=\\s*\"" + java.util.regex.Pattern.quote(version) + "\".*");
                    }
                    if (item.getName().equals("com/skymc/autoupdater/AutoUpdaterMod.class")) entrypoint = true;
                }
            }
            if (!metadata || !entrypoint) throw new IOException("Incomplete embedded updater/version mismatch");
        }
    }
    private static byte[] limited(InputStream in, int limit) throws IOException {
        byte[] bytes = in.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("Updater ZIP entry too large");
        return bytes;
    }
    static Path journal(Path game) { return StateFiles.root(game).resolve("self-update.json"); }
    static boolean hasPending(Path game) throws IOException { SafePaths.checkLinks(journal(game)); return Files.exists(journal(game)); }
    static void arm(Path game, Prepared prepared) throws IOException {
        if (prepared != null) StateFiles.write(journal(game), prepared.journal());
    }
    private static boolean matches(Path path, String hash) throws IOException {
        SafePaths.checkLinks(path);
        return Files.isRegularFile(path) && ConfigInstaller.sha256(path).equalsIgnoreCase(hash);
    }
    static void install(Path game) throws IOException { install(game, SelfUpdate::move); }
    static void install(Path game, Move move) throws IOException {
        if (!hasPending(game)) return;
        JsonObject entry = StateFiles.read(journal(game)); validate(entry);
        String oldName, oldHash;
        try { oldName = entry.get("oldName").getAsString(); oldHash = entry.get("oldSha256").getAsString(); }
        catch (RuntimeException error) { throw new IOException("Invalid updater journal", error); }
        SafePaths.segment(oldName);
        if (!PendingInstall.validModName(oldName) || !oldHash.matches("(?i)[a-f0-9]{64}")) throw new IOException("Invalid updater original");
        Path root = StateFiles.root(game), mods = game.resolve("mods");
        Path old = SafePaths.resolve(mods, oldName), target = SafePaths.resolve(mods, entry.get("fileName").getAsString());
        String hash = entry.get("sha256").getAsString().toLowerCase(Locale.ROOT);
        Path staged = SafePaths.resolve(root, "self-update/" + hash + ".jar");
        Path backup = SafePaths.resolve(root, "self-update/backups/" + oldHash.toLowerCase(Locale.ROOT) + ".jar");
        if (matches(target, hash) && (target.equals(old) || !Files.exists(old))) {
            validateJar(target, entry.get("version").getAsString()); Files.delete(journal(game)); return;
        }
        if (!matches(staged, hash) || Files.size(staged) != entry.get("size").getAsLong()) throw new IOException("Staged updater SHA256 mismatch");
        validateJar(staged, entry.get("version").getAsString());
        if (Files.exists(old) && !matches(old, oldHash) && !matches(old, hash)) throw new IOException("Installed updater changed since download");
        if (!target.equals(old) && Files.exists(target) && !matches(target, hash)) throw new IOException("Updater target collision");
        Files.createDirectories(backup.getParent());
        if (!matches(backup, oldHash)) {
            if (!matches(old, oldHash)) throw new IOException("Updater original/backup missing");
            Path part = SafePaths.resolve(backup.getParent(), oldHash.toLowerCase(Locale.ROOT) + ".part");
            try {
                Files.copy(old, part, StandardCopyOption.REPLACE_EXISTING);
                if (!matches(part, oldHash)) throw new IOException("Updater backup SHA256 mismatch");
                move(part, backup);
            } finally { Files.deleteIfExists(part); }
        }
        // The journal remains until the new filename is installed and the old filename is gone.
        Path temporary = SafePaths.resolve(mods, ".automodupdater-" + hash + ".part");
        try {
            if (!matches(target, hash) && !matches(old, hash)) {
                Files.copy(staged, temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!matches(temporary, hash)) throw new IOException("Updater copy SHA256 mismatch");
                move.move(temporary, old);
            }
            // Replace the original atomically first, then rename it: a crash always leaves a loadable updater.
            if (!target.equals(old) && !matches(target, hash)) move.move(old, target);
            if (!target.equals(old)) Files.deleteIfExists(old);
            if (!matches(target, hash)) throw new IOException("Installed updater SHA256 mismatch");
            Files.delete(journal(game));
        } catch (IOException error) {
            try {
                if (!target.equals(old) && matches(target, hash)) Files.delete(target);
                if (!matches(old, oldHash)) {
                    Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING);
                    move(temporary, old);
                }
            } catch (IOException rollback) { error.addSuppressed(rollback); }
            throw error;
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
}
