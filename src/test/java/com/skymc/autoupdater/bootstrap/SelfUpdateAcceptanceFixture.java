package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Test-only HTTPS publication for the unchanged Swing flow in a real isolated Forge profile. */
public final class SelfUpdateAcceptanceFixture {
    public static void main(String[] args) throws Exception {
        Path game = Path.of(args[0]), release = Path.of(args[1]);
        if (Files.exists(game)) throw new IllegalStateException("Use a new acceptance profile");
        Files.createDirectories(game.resolve("mods")); Files.createDirectories(game.resolve("config/acceptance"));
        Path fixture = game.resolve("fixture"); Files.createDirectories(fixture);
        Path next = SelfUpdateReleaseTest.future(release,fixture.resolve("future-test-only.jar"));
        Files.copy(release,game.resolve("mods/AutoModUpdater-2.1.jar"));
        Files.copy(Path.of("client/build/acceptance/acceptance-probe.jar"),game.resolve("mods/acceptance-probe.jar"));
        byte[] extra = SelfUpdateReleaseTest.rewrite(Files.readAllBytes(Path.of("client/build/acceptance/acceptance-extra.jar")),
                Map.of("acceptance-extra-version.txt","v2".getBytes(StandardCharsets.UTF_8)));
        Files.write(game.resolve("mods/acceptance-extra.jar"),extra);
        Files.writeString(game.resolve("config/acceptance/settings.toml"),"v2");
        Files.writeString(game.resolve("automated-case.txt"),"ui-restart");
        Files.writeString(game.resolve("self-update-expected-version.txt"),"3");
        try (var web = new SelfUpdateReleaseTest.TlsPublication(fixture)) {
            JsonObject updater = SelfUpdateTest.publication(next,"3"); updater.addProperty("url",web.base+"/updater.jar");
            JsonObject manifest = new JsonObject(); manifest.addProperty("schemaVersion",2); manifest.addProperty("minecraftVersion","1.20.1");
            manifest.addProperty("packId",StateFiles.PACK_ID); manifest.add("mods",new JsonArray()); manifest.add("configs",new JsonArray()); manifest.add("updater",updater);
            web.assets.put("/server-mods.json",manifest.toString().getBytes(StandardCharsets.UTF_8)); web.assets.put("/updater.jar",Files.readAllBytes(next));
            JsonObject config = new JsonObject(); config.addProperty("manifestUrl",web.base+"/server-mods.json"); config.addProperty("autoRestart",true);
            config.addProperty("theme","night"); config.addProperty("custom-preserve","unchanged");
            StateFiles.write(game.resolve("config/automodupdater.json"),config);
            Files.writeString(fixture.resolve("ready.txt"),web.base);
            long deadline = System.nanoTime()+240_000_000_000L;
            while (!Files.exists(fixture.resolve("stop")) && System.nanoTime()<deadline) Thread.sleep(250);
        }
    }
}
