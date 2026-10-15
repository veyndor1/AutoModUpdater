package com.skymc.autoupdater.bootstrap;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** Shared by game startup and post-exit helpers, including the previous release's lock. */
final class ProfileLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private ProfileLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    static ProfileLock acquire(Path game, long timeoutMillis) throws IOException, InterruptedException {
        Path root = game.resolve(".automodupdater"); SafePaths.checkLinks(root); Files.createDirectories(root);
        FileChannel channel = FileChannel.open(SafePaths.resolve(root,"session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        long until = System.nanoTime() + timeoutMillis * 1_000_000L;
        try {
            do {
                FileLock lock = null;
                try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) { }
                if (lock != null) return new ProfileLock(channel,lock);
                if (System.nanoTime() >= until) throw new IOException("Another game/helper is using this profile");
                Thread.sleep(50);
            } while (true);
        } catch (IOException | InterruptedException failure) { channel.close(); throw failure; }
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
