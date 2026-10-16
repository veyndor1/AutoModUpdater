package com.skymc.autoupdater.bootstrap;

/** Counts are published only after the entire release is verified and armed. */
public record UpdateReport(int mods, int configs, int removals, String error) {
    public UpdateReport(int mods, int configs, String error) { this(mods, configs, 0, error); }
    public boolean successful() { return error.isEmpty(); }
    public boolean changed() { return mods > 0 || configs > 0 || removals > 0; }
}
