package com.skymc.autoupdater.bootstrap;

/** Counts are published only after the entire release is verified and armed. */
public record UpdateReport(int mods, int configs, String error) {
    public boolean successful() { return error.isEmpty(); }
    public boolean changed() { return mods > 0 || configs > 0; }
}
