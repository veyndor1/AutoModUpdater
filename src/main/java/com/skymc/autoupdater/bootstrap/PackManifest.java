package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Accept only the Forge 1.20.1 publication contract. */
final class PackManifest {
    final JsonObject json;
    final JsonArray mods, configs;
    PackManifest(String text) throws IOException {
        try {
            json = JsonParser.parseString(text.replace("\uFEFF", "")).getAsJsonObject();
            if (json.get("schemaVersion").getAsInt() != 2
                    || !json.get("minecraftVersion").getAsString().equals("1.20.1")
                    || !json.get("packId").getAsString().equals(StateFiles.PACK_ID))
                throw new IOException("Publication does not match this updater");
            mods = json.getAsJsonArray("mods"); configs = json.getAsJsonArray("configs");
            if (mods == null || configs == null) throw new IOException("Missing publication sections");
            if (json.has("updater")) SelfUpdate.validate(json.getAsJsonObject("updater"));
            Set<String> names = new HashSet<>();
            for (JsonElement element : mods) {
                JsonObject mod = element.getAsJsonObject();
                String name = mod.get("fileName").getAsString();
                SafePaths.segment(name);
                if (!PendingInstall.validModName(name) || !names.add(name.toLowerCase(Locale.ROOT)))
                    throw new IOException("Invalid/duplicate mod name");
                mod.get("url").getAsString();
                if (mod.has("sha1") && !mod.get("sha1").getAsString().matches("(?i)[a-f0-9]{40}"))
                    throw new IOException("Invalid mod SHA1");
            }
            names.clear();
            for (JsonElement element : configs) {
                JsonObject entry = element.getAsJsonObject();
                String path = entry.get("path").getAsString(); SafePaths.segment(path);
                if (path.equalsIgnoreCase("automodupdater.json") || !names.add(path.toLowerCase(Locale.ROOT)))
                    throw new IOException("Reserved/duplicate config target");
                String type = entry.get("type").getAsString();
                if (!type.equals("directory") && !type.equals("file")) throw new IOException("Invalid config type");
                entry.get("url").getAsString();
                if (!entry.get("sha256").getAsString().matches("(?i)[a-f0-9]{64}")) throw new IOException("Invalid config SHA256");
                ConfigInstaller.validateInventory(entry);
            }
        } catch (RuntimeException e) { throw new IOException("Invalid v2 publication", e); }
    }
}
