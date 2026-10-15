package com.skymc.autoupdater.bootstrap;

import java.nio.file.*;

/** Test-only preparation of a genuine pending mod/config release for startup recovery. */
public final class AcceptanceSetupFixture {
    public static void main(String[] args) throws Exception {
        Path game = Path.of(args[0]);
        try (ProfileLock lock = ProfileLock.acquire(game,0)) {
            ModDownloader.runSync(game.toFile(),game.resolve("mods").toFile());
            if (!PendingInstall.hasPending(game) || !Files.exists(StateFiles.root(game).resolve("config-transaction.json")))
                throw new IllegalStateException("Recovery fixture did not stage a complete mod/config release");
        }
    }
}
