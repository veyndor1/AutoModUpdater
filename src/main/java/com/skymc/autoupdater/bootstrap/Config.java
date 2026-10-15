package com.skymc.autoupdater.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Ayarlar.
 *
 * 1) DEFAULT_MANIFEST_URL: SUNUCU SAHIBI build oncesi burayi kendi manifest adresiyle degistirir.
 * 2) Istege bagli: oyuncunun .minecraft/config/automodupdater.json dosyasi varsa oradan
 *    "manifestUrl" ve "autoRestart" okunur (modu yeniden derlemeden degistirebilmek icin).
 */
public class Config {

    // ====================================================================
    //  SUNUCU SAHIBI: Asagidaki URL'i kendi server-mods.json adresinle degistir!
    // ====================================================================
    public static final String DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/veyndor1/mcmods2/main/server-mods.json";

    public static String manifestUrl = DEFAULT_MANIFEST_URL;
    public static boolean autoRestart = true;
    public static boolean nightTheme = true;
    public static boolean reducedMotion = false;

    public static void load(File mcDir) {
        manifestUrl = DEFAULT_MANIFEST_URL;
        autoRestart = true;
        nightTheme = true;
        reducedMotion = false;
        try {
            File configDir = new File(mcDir, "config");
            File cfg = new File(configDir, "automodupdater.json");
            SafePaths.checkLinks(cfg.toPath());

            if (!cfg.exists()) {
                writeDefault(configDir, cfg);
                return;
            }

            String json = new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("manifestUrl")) {
                String u = o.get("manifestUrl").getAsString();
                if (u != null && !u.trim().isEmpty()) manifestUrl = u.trim();
            }
            if (o.has("autoRestart")) {
                autoRestart = o.get("autoRestart").getAsBoolean();
            }
            if (o.has("theme")) nightTheme = !o.get("theme").getAsString().equals("light");
            if (o.has("reducedMotion")) reducedMotion = o.get("reducedMotion").getAsBoolean();
        } catch (Throwable t) {
            System.out.println("[AutoModUpdater] config okunamadi, varsayilanlar kullaniliyor: " + t);
        }
    }

    /** Preserve unrelated and unknown settings; never overwrite malformed JSON. */
    static synchronized void savePresentation(File game, boolean night, boolean reduced) throws java.io.IOException {
        var path = game.toPath().resolve("config/automodupdater.json");
        SafePaths.checkLinks(path);
        JsonObject data = Files.exists(path) ? StateFiles.read(path) : new JsonObject();
        data.addProperty("theme", night ? "night" : "light");
        data.addProperty("reducedMotion", reduced);
        StateFiles.write(path, data);
        nightTheme = night; reducedMotion = reduced;
    }

    private static void writeDefault(File configDir, File cfg) {
        try {
            if (!configDir.exists()) configDir.mkdirs();
            String def = "{\n"
                    + "  \"manifestUrl\": \"" + DEFAULT_MANIFEST_URL + "\",\n"
                    + "  \"autoRestart\": true\n"
                    + "}\n";
            Files.write(cfg.toPath(), def.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }
}
