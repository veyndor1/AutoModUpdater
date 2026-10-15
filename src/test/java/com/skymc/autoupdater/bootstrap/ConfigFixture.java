package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Independent HTTP/ZIP fixture; expected content is asserted at the filesystem boundary. */
final class ConfigFixture implements AutoCloseable {
    final HttpServer server;
    final String base;
    final Map<String,byte[]> assets = new HashMap<>();
    final AtomicInteger downloads = new AtomicInteger();
    String manifest;
    ConfigFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] bytes;
            if (path.equals("/manifest")) bytes = manifest.getBytes(StandardCharsets.UTF_8);
            else { bytes = assets.get(path); downloads.incrementAndGet(); }
            if (bytes == null) exchange.sendResponseHeaders(404, -1);
            else { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            exchange.close();
        }); server.start();
    }
    static String hash(byte[] bytes, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }
    JsonObject file(String name, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); String sha = hash(bytes,"SHA-256");
        assets.put("/" + sha, bytes);
        JsonObject entry = new JsonObject(); entry.addProperty("path",name); entry.addProperty("type","file");
        entry.addProperty("url",base + "/" + sha); entry.addProperty("sha256",sha); entry.addProperty("size",bytes.length);
        return entry;
    }
    JsonObject folder(String name, Map<String,String> content, String... emptyDirs) throws Exception {
        Set<String> dirs = new TreeSet<>(Arrays.asList(emptyDirs)); JsonArray files = new JsonArray();
        for (var item : new TreeMap<>(content).entrySet()) {
            int index = item.getKey().lastIndexOf('/');
            while (index >= 0) { dirs.add(item.getKey().substring(0,index)); index = item.getKey().lastIndexOf('/',index - 1); }
            byte[] data = item.getValue().getBytes(StandardCharsets.UTF_8);
            JsonObject f = new JsonObject(); f.addProperty("path",item.getKey()); f.addProperty("sha256",hash(data,"SHA-256")); f.addProperty("size",data.length); files.add(f);
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(data)) {
            for (String dir : dirs) { zip.putNextEntry(new ZipEntry(dir + "/")); zip.closeEntry(); }
            for (var item : new TreeMap<>(content).entrySet()) { zip.putNextEntry(new ZipEntry(item.getKey())); zip.write(item.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        }
        String sha = hash(data.toByteArray(),"SHA-256"); assets.put("/" + sha,data.toByteArray());
        JsonObject entry = new JsonObject(); entry.addProperty("path",name); entry.addProperty("type","directory");
        entry.addProperty("url",base + "/" + sha); entry.addProperty("sha256",sha); entry.addProperty("size",data.size());
        JsonArray directories = new JsonArray(); dirs.forEach(directories::add); entry.add("directories",directories); entry.add("files",files);
        return entry;
    }
    JsonObject mod(String name, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); assets.put("/mod/" + name,bytes);
        JsonObject entry = new JsonObject(); entry.addProperty("fileName",name); entry.addProperty("url",base + "/mod/" + name);
        entry.addProperty("sha1",hash(bytes,"SHA-1")); return entry;
    }
    static JsonArray array(JsonObject... entries) { JsonArray a = new JsonArray(); for (JsonObject e : entries) a.add(e); return a; }
    void publish(JsonArray mods, JsonArray configs) {
        JsonObject root = new JsonObject(); root.addProperty("schemaVersion",2); root.addProperty("minecraftVersion","1.20.1");
        root.addProperty("packId",StateFiles.PACK_ID); root.add("mods",mods); root.add("configs",configs); manifest = root.toString();
    }
    public void close() { server.stop(0); }
}
