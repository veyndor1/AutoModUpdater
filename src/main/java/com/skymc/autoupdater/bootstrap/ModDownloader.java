package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Early downloads are armed only after the complete mod/config release is verified. */
public final class ModDownloader {
    static File stagingDir(File game) { return StateFiles.root(game.toPath()).resolve("staging").toFile(); }
    public static void runSync(File gameFile, File modsDir) {
        download(gameFile, modsDir, UpdateProgress.NONE);
    }
    static UpdateReport download(File gameFile, File modsDir, UpdateProgress window) {
        UpdateState.gameDir = gameFile;
        UpdateState.newModsDownloaded = 0; UpdateState.configEntriesUpdated = 0;
        UpdateState.errorMessage = "";
        UpdateState.downloadedNames = new ArrayList<>();
        UpdateProgress.Kind activeKind = null; String activeId = "", activeName = "";
        try {
            Path game = gameFile.toPath().toAbsolutePath().normalize();
            Config.load(gameFile);
            SafePaths.checkLinks(StateFiles.root(game)); SafePaths.checkLinks(modsDir.toPath());
            PackManifest manifest = new PackManifest(httpGetString(Config.manifestUrl));
            SelfUpdate.protectModTargets(game, manifest.mods);
            if (manifest.json.has("updater") && !new URL(Config.manifestUrl).getProtocol().equals("https"))
                throw new IOException("Updater publication requires HTTPS");
            ConfigInstaller configs = new ConfigInstaller(game, window);
            Path staging = stagingDir(gameFile).toPath(); Files.createDirectories(staging);
            Map<String,String> ready = new LinkedHashMap<>(); List<String> names = new ArrayList<>();
            for (JsonElement value : manifest.mods) {
                JsonObject entry = value.getAsJsonObject();
                window.inventory(UpdateProgress.Kind.MOD, entry.get("fileName").getAsString(),
                        entry.has("name") ? entry.get("name").getAsString() : entry.get("fileName").getAsString());
            }
            for (JsonElement value : manifest.configs) {
                String path = value.getAsJsonObject().get("path").getAsString();
                window.inventory(UpdateProgress.Kind.CONFIG, path, path);
            }
            for (JsonElement value : manifest.mods) {
                JsonObject entry = value.getAsJsonObject(); String name = entry.get("fileName").getAsString();
                String display = entry.has("name") ? entry.get("name").getAsString() : name;
                activeKind = UpdateProgress.Kind.MOD; activeId = name; activeName = display;
                window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.CHECKING, 0, -1);
                Path installed = SafePaths.resolve(modsDir.toPath(), name), staged = SafePaths.resolve(staging, name);
                String expected = entry.has("sha1") ? entry.get("sha1").getAsString() : null;
                if (Files.isRegularFile(installed) && (expected == null || sha1Of(installed.toFile()).equalsIgnoreCase(expected))) {
                    log("Guncel: " + name);
                    window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.CURRENT, 0, -1);
                } else {
                    if (expected == null || !Files.isRegularFile(staged) || !sha1Of(staged.toFile()).equalsIgnoreCase(expected)) {
                        window.status("Mod indiriliyor: " + display);
                        Path part = SafePaths.resolve(staging, name + ".part");
                        try {
                            window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.DOWNLOADING, 0, -1);
                            httpDownload(entry.get("url").getAsString(), part.toFile(), Long.MAX_VALUE,
                                    (bytes, total) -> window.item(UpdateProgress.Kind.MOD, name, display,
                                            UpdateProgress.Phase.DOWNLOADING, bytes, total));
                            window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.VERIFYING, 0, -1);
                            if (expected != null && !sha1Of(part.toFile()).equalsIgnoreCase(expected)) throw new IOException("Mod SHA1 mismatch: " + name);
                            if (SelfUpdate.isDistribution(part)) throw new IOException("Updater JAR must use the updater publication section");
                            Files.move(part, staged, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(part); }
                    }
                    if (SelfUpdate.isDistribution(staged)) throw new IOException("Updater JAR must use the updater publication section");
                    ready.put(name, sha1Of(staged.toFile()));
                    names.add(display);
                    window.item(UpdateProgress.Kind.MOD, name, display, UpdateProgress.Phase.READY, 0, -1);
                }
            }
            activeKind = null;
            SelfUpdate.Prepared updater = SelfUpdate.prepare(game, manifest.json.getAsJsonObject("updater"), window);
            ConfigInstaller.Prepared prepared = configs.prepare(manifest.configs);
            configs.arm(prepared, ready);
            if (!ready.isEmpty()) PendingInstall.recordBatch(game, ready);
            SelfUpdate.arm(game, updater);
            int installedConfigs = configs.recover(false);
            int updates = ready.size() + (updater == null ? 0 : 1);
            if (updater != null) names.add(updater.name());
            UpdateState.newModsDownloaded = updates; UpdateState.configEntriesUpdated = installedConfigs;
            UpdateState.downloadedNames = names;
            log("Hazir: " + ready.size() + " mod, " + prepared.entries().size() + " config girdisi");
            return new UpdateReport(updates, prepared.entries().size(), "");
        } catch (ConfigInstaller.RecoveryException fatal) {
            throw new IllegalStateException("Config recovery failed; refusing an incomplete game startup", fatal);
        } catch (Exception error) {
            Throwable detail = error;
            while (detail.getCause() != null && detail.getCause() != detail) detail = detail.getCause();
            UpdateState.errorMessage = detail.getClass().getSimpleName() + ": " + detail.getMessage();
            log("Guncelleme tamamlanamadi; mevcut dosyalar korunuyor: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            if (activeKind != null) window.item(activeKind, activeId, activeName, UpdateProgress.Phase.FAILED, 0, -1);
            window.status("Güncelleme uygulanamadı. Mevcut dosyalar korundu.");
            return new UpdateReport(0, 0, UpdateState.errorMessage);
        }
    }
    public static int installStaged(File game) {
        try { return PendingInstall.install(game.toPath()); }
        catch (IOException e) { log("Kurulum bekliyor: " + e.getClass().getSimpleName()); return 0; }
    }
    static String sha1Of(File file) throws IOException { return ConfigInstaller.digest(file.toPath(), "SHA-1"); }
    static HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address); int redirects = 0;
        while (true) {
            if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https")) throw new IOException("Unsupported download protocol");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false); connection.setRequestProperty("User-Agent", "AutoModUpdater/" + SelfUpdate.version());
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location"); connection.disconnect();
                if (location == null || ++redirects > 6) throw new IOException("Too many redirects");
                URL next = new URL(url, location);
                if (url.getProtocol().equals("https") && !next.getProtocol().equals("https")) throw new IOException("HTTPS redirect downgrade refused");
                url = next; continue;
            }
            if (code != 200) { connection.disconnect(); throw new IOException("HTTP " + code); }
            return connection;
        }
    }
    private static String httpGetString(String address) throws IOException {
        HttpURLConnection connection = open(address);
        try (InputStream in = connection.getInputStream()) {
            byte[] bytes = in.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024) throw new IOException("Manifest too large");
            return new String(bytes, StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }
    static void httpDownload(String address, File destination) throws IOException {
        httpDownload(address, destination, Long.MAX_VALUE);
    }
    static void httpDownload(String address, File destination, long limit) throws IOException {
        httpDownload(address, destination, limit, (bytes, total) -> {});
    }
    @FunctionalInterface interface Transfer { void update(long bytes, long total); }
    static void httpDownload(String address, File destination, long limit, Transfer transfer) throws IOException {
        HttpURLConnection connection = open(address);
        long size = connection.getContentLengthLong();
        long lastNotification = 0;
        try (InputStream in = new BufferedInputStream(connection.getInputStream()); OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536]; int n; long written = 0;
            while ((n = in.read(buffer)) >= 0) {
                if (n > limit - written) throw new IOException("Download exceeds declared size");
                written += n; out.write(buffer, 0, n);
                long now = System.nanoTime();
                if (now - lastNotification > 80_000_000L) { transfer.update(written, size); lastNotification = now; }
            }
            transfer.update(written, size);
        } finally { connection.disconnect(); }
    }
    private static void log(String text) { System.out.println("[AutoModUpdater] " + text); }
}
