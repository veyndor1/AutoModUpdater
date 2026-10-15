package com.skymc.autoupdater.bootstrap;

import com.google.gson.JsonObject;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/** Invoked before every locator's scanMods; downloads must remain outside mods/. */
public final class UpdaterLocator extends AbstractJarFileModLocator {
    private Path client;
    private ProfileLock sessionLock;
    @Override public String name() { return "AutoModUpdater early updater"; }
    @Override public void initArguments(Map<String, ?> arguments) {
        if (FMLLoader.getDist() != Dist.CLIENT) return;
        Path game = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        try {
            SafePaths.checkLinks(StateFiles.root(game));
            client = extractClient(game);
            Files.createDirectories(StateFiles.root(game));
            try { sessionLock = ProfileLock.acquire(game, 10000); }
            catch (IOException busy) { throw new IllegalStateException("Another game/helper is using this profile", busy); }
            Config.load(game.toFile());
            try { new ConfigInstaller(game, null).recover(true); }
            catch (IOException recovery) {
                if (Files.exists(StateFiles.root(game).resolve("config-transaction.json")))
                    throw new IllegalStateException("Config recovery incomplete; game startup stopped", recovery);
                System.out.println("[AutoModUpdater] Config guncellemesi geri alindi; mevcut ayarlar korunuyor.");
            }
            // Recovery must finish in a fresh JVM, including service/transformer discovery.
            if (PendingInstall.hasPending(game) || SelfUpdate.hasPending(game)) {
                JsonObject state = StateFiles.snapshot(game);
                state.addProperty("recovery", true);
                StateFiles.write(StateFiles.session(game, ProcessHandle.current().pid()), state);
                HelperLauncher.start(game, state);
                HelperLauncher.requestRestart(game, state, Config.autoRestart);
                System.out.println("[AutoModUpdater] Onceki kurulum tamamlanacak; oyun yeniden acilmali.");
                System.exit(0);
                return;
            }
            StartupCoordinator.run(game, FMLPaths.MODSDIR.get());
        } catch (IllegalStateException fatal) {
            throw fatal;
        } catch (Exception e) {
            System.out.println("[AutoModUpdater] Erken guncelleme atlandi: " + e);
        }
    }
    @Override public Stream<Path> scanCandidates() { return client == null ? Stream.empty() : Stream.of(client); }
    private static Path extractClient(Path game) throws Exception {
        byte[] bytes;
        try (InputStream in = UpdaterLocator.class.getResourceAsStream("/embedded/automodupdater-client.jar")) {
            if (in == null) throw new IOException("Gomulu arayuz JAR'i bulunamadi");
            bytes = in.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path dir = StateFiles.root(game).resolve("client");
        Files.createDirectories(dir);
        Path dest = dir.resolve("automodupdater-client-" + hash + ".jar");
        if (!Files.exists(dest) || !Arrays.equals(Files.readAllBytes(dest), bytes)) {
            Path tmp = Files.createTempFile(dir, "client-", ".part");
            Files.write(tmp, bytes);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }
}
