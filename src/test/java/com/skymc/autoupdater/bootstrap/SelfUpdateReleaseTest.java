package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("release")
class SelfUpdateReleaseTest {
    @TempDir Path temp;
    Path release() { return Path.of(System.getProperty("updater.release")); }
    static byte[] rewrite(byte[] original, Map<String,byte[]> overrides) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(original)); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                SelfUpdateTest.put(zip,entry.getName(),overrides.containsKey(entry.getName()) ? overrides.get(entry.getName()) : in.readAllBytes());
            }
        }
        return out.toByteArray();
    }
    static Path future(Path release, Path target) throws Exception {
        byte[] embedded;
        try (ZipFile zip = new ZipFile(release.toFile())) {
            try (InputStream in = zip.getInputStream(zip.getEntry(SelfUpdate.CLIENT))) { embedded = in.readAllBytes(); }
        }
        byte[] metadata;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(embedded))) {
            byte[] found = null;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                if (entry.getName().equals("META-INF/mods.toml")) found = zip.readAllBytes();
            metadata = Objects.requireNonNull(found);
        }
        embedded = rewrite(embedded,Map.of("META-INF/mods.toml",new String(metadata,StandardCharsets.UTF_8).replace("version=\"2.0\"","version=\"3\"").getBytes(StandardCharsets.UTF_8)));
        byte[] build = "version=3\nminecraftVersion=1.20.1\nforgeMajor=47\njavaMajor=17\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target,rewrite(Files.readAllBytes(release),Map.of(SelfUpdate.CLIENT,embedded,SelfUpdate.BUILD,build)));
        SelfUpdate.validateJar(target,"3"); return target;
    }
    @Test void httpsPublicationToObfuscatedHelperWaitsForExitThenUpdatesAndRelaunches() throws Exception {
        Path game = temp.resolve("Ömer profile"), mods = game.resolve("mods"); Files.createDirectories(mods);
        Path old = mods.resolve("old-updater.jar"); Files.copy(release(),old);
        Path next = future(release(),temp.resolve("future.jar"));
        JsonObject entry = SelfUpdateTest.publication(next,"3");
        byte[] settings;
        try (TlsPublication web = new TlsPublication(temp)) {
            entry.addProperty("url",web.base+"/new.jar");
            JsonObject manifest = new JsonObject(); manifest.addProperty("schemaVersion",2); manifest.addProperty("minecraftVersion","1.20.1");
            manifest.addProperty("packId",StateFiles.PACK_ID); manifest.add("mods",new JsonArray()); manifest.add("configs",new JsonArray()); manifest.add("updater",entry);
            web.assets.put("/server-mods.json",manifest.toString().getBytes(StandardCharsets.UTF_8)); web.assets.put("/new.jar",Files.readAllBytes(next));
            Files.createDirectories(game.resolve("config"));
            Files.writeString(game.resolve("config/automodupdater.json"),"{\"manifestUrl\":\""+web.base+"/server-mods.json\",\"autoRestart\":true,\"theme\":\"light\",\"custom\":42}");
            settings = Files.readAllBytes(game.resolve("config/automodupdater.json"));
            UpdateReport report = ModDownloader.download(game.toFile(),mods.toFile(),UpdateProgress.NONE);
            assertTrue(report.successful(),report.error()); assertEquals(1,report.mods()); assertTrue(report.changed());
            assertEquals(List.of("Auto Mod Updater 3"),UpdateState.downloadedNames);
            assertFalse(PendingInstall.hasPending(game)); assertTrue(SelfUpdate.hasPending(game));
            assertEquals(-1,Files.mismatch(release(),old));
            // SHA/version checks happen before any journal can be armed, including over real TLS.
            Path clean = temp.resolve("bad-download"); Files.createDirectories(clean.resolve("mods")); Files.copy(release(),clean.resolve("mods/updater.jar"));
            Files.createDirectories(clean.resolve("config")); Files.write(clean.resolve("config/automodupdater.json"),settings);
            web.assets.put("/new.jar","tampered".getBytes(StandardCharsets.UTF_8));
            UpdateReport failed = ModDownloader.download(clean.toFile(),clean.resolve("mods").toFile(),UpdateProgress.NONE);
            assertFalse(failed.successful()); assertFalse(SelfUpdate.hasPending(clean));
        }
        String executable = Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        Path held = game.resolve("lock-ready");
        String ps = "$s=[IO.File]::Open('"+old.toString().replace("'","''")+"',[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None);"
                +"[IO.File]::WriteAllText('"+held.toString().replace("'","''")+"','ready');Start-Sleep -Seconds 120";
        Process parent = new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",ps).start();
        Process helper = null;
        try {
            ReleaseIntegrationTest.awaitFile(held,10);
            Path marker = game.resolve("relaunched.txt");
            List<String> command = List.of(executable,"-cp",Path.of(RelaunchFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),RelaunchFixture.class.getName(),marker.toString());
            ReleaseIntegrationTest fixture = new ReleaseIntegrationTest(); fixture.game = game;
            helper = fixture.startHelper(parent.pid(),command);
            ReleaseIntegrationTest.awaitFile(StateFiles.root(game).resolve("ready-"+parent.pid()+".json"),10);
            JsonObject request = new JsonObject(); request.addProperty("sessionId","test-session"); request.addProperty("relaunch",true);
            StateFiles.write(StateFiles.root(game).resolve("request-"+parent.pid()+".json"),request);
            StateFiles.write(StateFiles.root(game).resolve("install-"+parent.pid()+".json"),request);
            Thread.sleep(400); assertTrue(helper.isAlive()); assertFalse(Files.exists(marker));
            assertFalse(Files.exists(mods.resolve("AutoModUpdater-3.jar")));
            parent.destroyForcibly(); assertTrue(parent.waitFor(10,TimeUnit.SECONDS));
            assertTrue(helper.waitFor(25,TimeUnit.SECONDS));
            ReleaseIntegrationTest.awaitFile(marker,10);
            JsonObject result = StateFiles.read(StateFiles.root(game).resolve("result-"+parent.pid()+".json"));
            assertTrue(result.get("installed").getAsBoolean(),result.toString()); assertTrue(result.get("relaunched").getAsBoolean());
            assertFalse(Files.exists(old)); assertFalse(SelfUpdate.hasPending(game));
            assertEquals(-1,Files.mismatch(next,mods.resolve("AutoModUpdater-3.jar")));
            assertArrayEquals(settings,Files.readAllBytes(game.resolve("config/automodupdater.json")));
            assertEquals(-1,Files.mismatch(release(),StateFiles.root(game).resolve("self-update/backups/"+ConfigInstaller.sha256(release())+".jar")));
            ProcessHandle.of(result.get("childPid").getAsLong()).ifPresent(child -> { try { child.onExit().get(10,TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); } });
        } finally {
            parent.destroyForcibly(); parent.waitFor(10,TimeUnit.SECONDS);
            if (helper != null && helper.isAlive()) { helper.destroyForcibly(); helper.waitFor(10,TimeUnit.SECONDS); }
        }
    }
    @Test void releasePublicationIncludesExactFullJarAndVersionWithoutDuplicatingMods() throws Exception {
        Path mods = temp.resolve("source-mods"), configs = temp.resolve("source-configs"), out = temp.resolve("publication");
        Files.createDirectories(mods); Files.createDirectories(configs);
        Process process = new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",Path.of("make-manifest.ps1").toAbsolutePath().toString(),
                "-ModsDir",mods.toString(),"-ConfigsDir",configs.toString(),"-OutDir",out.toString(),"-UpdaterJar",release().toString()).redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertTrue(process.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,process.exitValue(),log);
        PackManifest manifest = new PackManifest(Files.readString(out.resolve("server-mods.json")));
        JsonObject entry = manifest.json.getAsJsonObject("updater"); assertEquals("2.0",entry.get("version").getAsString());
        assertEquals(0,manifest.mods.size());
        Path published = out.resolve("updater/"+entry.get("sha256").getAsString()+"/AutoModUpdater-2.0.jar");
        assertEquals(-1,Files.mismatch(release(),published)); SelfUpdate.validateJar(published,"2.0");
    }
    static final class TlsPublication implements AutoCloseable {
        final HttpsServer server;
        final String base;
        final Map<String,byte[]> assets = new java.util.concurrent.ConcurrentHashMap<>();
        final SSLSocketFactory previous = HttpsURLConnection.getDefaultSSLSocketFactory();
        TlsPublication(Path temp) throws Exception {
            Path store = temp.resolve("test-tls.p12");
            Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString(),"-genkeypair","-alias","local-test","-keyalg","RSA","-keysize","2048","-storetype","PKCS12",
                    "-keystore",store.toString(),"-storepass","test-password","-keypass","test-password","-dname","CN=127.0.0.1","-ext","SAN=ip:127.0.0.1","-validity","2","-noprompt").redirectErrorStream(true).start();
            String log = new String(keytool.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(keytool.waitFor(15,TimeUnit.SECONDS)); assertEquals(0,keytool.exitValue(),log);
            KeyStore keys = KeyStore.getInstance("PKCS12"); try (InputStream in = Files.newInputStream(store)) { keys.load(in,"test-password".toCharArray()); }
            KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys,"test-password".toCharArray());
            TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
            SSLContext context = SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(),tm.getTrustManagers(),null);
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setHttpsConfigurator(new HttpsConfigurator(context));
            base = "https://127.0.0.1:"+server.getAddress().getPort();
            server.createContext("/",exchange -> {
                if (exchange.getRequestURI().getPath().equals("/downgrade")) {
                    exchange.getResponseHeaders().add("Location","http://127.0.0.1:9/rejected"); exchange.sendResponseHeaders(302,-1);
                } else {
                    byte[] bytes = assets.get(exchange.getRequestURI().getPath());
                    if (bytes == null) exchange.sendResponseHeaders(404,-1);
                    else { exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); }
                }
                exchange.close();
            }); server.start();
            assertThrows(IOException.class,() -> SelfUpdate.download(base+"/downgrade",temp.resolve("downgrade.part"),(a,b)->{}));
        }
        public void close() { server.stop(0); HttpsURLConnection.setDefaultSSLSocketFactory(previous); }
    }
}
