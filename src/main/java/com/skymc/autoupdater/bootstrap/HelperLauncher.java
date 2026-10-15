package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class HelperLauncher {
    public static void start(Path game, JsonObject state) throws Exception {
        long pid = ProcessHandle.current().pid();
        Path source;
        try {
            source = Path.of(HelperLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(source)) source = findDistribution(game);
        } catch (java.nio.file.FileSystemNotFoundException | IllegalArgumentException e) {
            source = findDistribution(game);
        }
        Path helperDir = StateFiles.root(game).resolve("helper");
        Files.createDirectories(helperDir);
        byte[] bytes = Files.readAllBytes(source);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path jar = helperDir.resolve("installer-" + digest + ".jar");
        if (!Files.exists(jar) || !Arrays.equals(Files.readAllBytes(jar), bytes)) Files.write(jar, bytes);
        JsonObject payload = new JsonObject();
        payload.addProperty("pid", pid);
        payload.addProperty("sessionId", state.get("sessionId").getAsString());
        payload.addProperty("gameDir", game.toString());
        payload.addProperty("cwd", System.getProperty("user.dir"));
        JsonArray command = new JsonArray();
        try { LaunchCommand.capture().forEach(command::add); }
        catch (Exception e) { System.out.println("[AutoModUpdater] Otomatik acilis komutu alinamadi; elle acilis kullanilacak."); }
        payload.add("command", command);
        JsonObject owner = new JsonObject(); owner.add("sessionId", state.get("sessionId"));
        StateFiles.write(StateFiles.root(game).resolve("installer-owner.json"), owner);
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "javaw.exe" : "java").toString();
        Process helper = new ProcessBuilder(executable, "-cp", jar.toString(), InstallerHelper.class.getName())
                .directory(game.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        boolean accepted = false;
        try {
        // Authentication arguments are sent in memory, never written to a status file/log.
        try (OutputStream out = helper.getOutputStream()) { out.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
        Path ready = StateFiles.root(game).resolve("ready-" + pid + ".json");
        long until = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < until && helper.isAlive()) {
            if (Files.exists(ready)) {
                JsonObject receipt = StateFiles.read(ready);
                if (receipt.get("sessionId").equals(state.get("sessionId"))
                        && receipt.has("helperPid") && receipt.get("helperPid").getAsLong() == helper.pid()) {
                    state.addProperty("helperReady", true);
                    state.addProperty("canRelaunch", command.size() > 1);
                    StateFiles.write(StateFiles.session(game, pid), state);
                    accepted = true;
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new IOException("Kurulum yardimcisi hazir olmadi");
        } finally {
            // A failed handshake must not leave another installer waiting on the same parent.
            if (!accepted) {
                state.addProperty("helperReady", false);
                helper.destroy();
                if (!helper.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) helper.destroyForcibly();
            }
        }
    }
    private static Path findDistribution(Path game) throws Exception {
        try (var jars = Files.list(game.resolve("mods"))) {
            for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                try (var zip = new java.util.jar.JarFile(jar.toFile())) {
                    if (zip.getJarEntry("com/skymc/autoupdater/bootstrap/UpdaterLocator.class") != null
                            && zip.getJarEntry("embedded/automodupdater-client.jar") != null) return jar;
                }
            }
        }
        throw new IOException("Dagitim JAR'i bulunamadi");
    }
    public static void requestRestart(Path game, JsonObject state, boolean relaunch) throws IOException {
        JsonObject request = new JsonObject();
        request.add("sessionId", state.get("sessionId"));
        request.addProperty("relaunch", relaunch);
        StateFiles.write(StateFiles.root(game).resolve("request-" + ProcessHandle.current().pid() + ".json"), request);
    }
}
