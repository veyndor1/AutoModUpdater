package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Independent JVM: survives game shutdown, installs locked jars, then optionally relaunches. */
public final class InstallerHelper {
    public static void main(String[] args) {
        Path result = null;
        try {
            JsonObject payload = JsonParser.parseString(new String(System.in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            Path game = Path.of(payload.get("gameDir").getAsString());
            long pid = payload.get("pid").getAsLong();
            Path root = StateFiles.root(game);
            result = root.resolve("result-" + pid + ".json");
            JsonObject receipt = new JsonObject();
            receipt.add("sessionId", payload.get("sessionId"));
            receipt.addProperty("helperPid", ProcessHandle.current().pid());
            StateFiles.write(root.resolve("ready-" + pid + ".json"), receipt);
            Optional<ProcessHandle> parent = ProcessHandle.of(pid);
            if (parent.isPresent()) {
                var exit = parent.get().onExit();
                Path installRequest = root.resolve("install-" + pid + ".json");
                while (!exit.isDone()) {
                    try { exit.get(200, TimeUnit.MILLISECONDS); }
                    catch (TimeoutException ignored) {
                        if (Files.exists(installRequest)) {
                            JsonObject request = StateFiles.read(installRequest);
                            if (request.get("sessionId").equals(payload.get("sessionId"))) PendingInstall.install(game);
                            Files.deleteIfExists(installRequest);
                        }
                    }
                }
            }
            try (ProfileLock profile = ProfileLock.acquire(game, 15000)) {
            Path ownerFile = root.resolve("installer-owner.json");
            if (Files.exists(ownerFile) && !StateFiles.read(ownerFile).get("sessionId").equals(payload.get("sessionId"))) {
                receipt.addProperty("installed", false); receipt.addProperty("relaunched", false); receipt.addProperty("superseded", true);
                StateFiles.write(result, receipt); Files.deleteIfExists(root.resolve("ready-" + pid + ".json"));
                return;
            }
            // Release Windows handles and antivirus transient locks before retrying.
            boolean pending = true;
            for (int attempt = 0; attempt < 20 && pending; attempt++) {
                PendingInstall.install(game);
                pending = PendingInstall.hasPending(game);
                if (pending) Thread.sleep(500);
            }
            boolean relaunch = false;
            if (!pending) {
                new ConfigInstaller(game, null).recover(true);
                for (int attempt = 0; attempt < 20 && SelfUpdate.hasPending(game); attempt++) {
                    try { SelfUpdate.install(game); }
                    catch (java.io.IOException locked) {
                        if (attempt == 19) throw locked;
                        Thread.sleep(500);
                    }
                }
                pending = SelfUpdate.hasPending(game);
            }
            Path requestFile = root.resolve("request-" + pid + ".json");
            if (Files.exists(requestFile)) {
                JsonObject request = StateFiles.read(requestFile);
                relaunch = request.get("sessionId").equals(payload.get("sessionId")) && request.get("relaunch").getAsBoolean();
            }
            receipt.addProperty("installed", !pending);
            receipt.addProperty("configsInstalled", !Files.exists(root.resolve("config-transaction.json")));
            receipt.addProperty("relaunched", false);
            if (relaunch && !pending && payload.getAsJsonArray("command").size() > 1) {
                List<String> command = new ArrayList<>();
                payload.getAsJsonArray("command").forEach(a -> command.add(a.getAsString()));
                Process child = new ProcessBuilder(command).directory(Path.of(payload.get("cwd").getAsString()).toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                receipt.addProperty("relaunched", true);
                receipt.addProperty("childPid", child.pid());
            }
            StateFiles.write(result, receipt);
            Files.deleteIfExists(root.resolve("ready-" + pid + ".json"));
            Files.deleteIfExists(requestFile);
            }
        } catch (Exception e) {
            if (result != null) try {
                JsonObject failure = new JsonObject();
                failure.addProperty("installed", false);
                failure.addProperty("error", e.getClass().getSimpleName());
                StateFiles.write(result, failure);
            } catch (Exception ignored) { }
        }
    }
}
