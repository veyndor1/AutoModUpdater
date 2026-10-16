package com.skymc.autoupdater.bootstrap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Early SERVICE-layer download counters. GAME-layer UI receives a StateFiles snapshot;
 * these static fields are never shared across Forge module layers.
 */
public class UpdateState {

    /** Game root, set by the early locator. */
    public static File gameDir = null;

    /** Staged mod JAR count; only this counter triggers the restart prompt. */
    public static int newModsDownloaded = 0;
    public static int modsToRemove = 0;
    public static List<String> removalNames = new ArrayList<>();

    /** Published config roots changed during this session. */
    public static int configEntriesUpdated = 0;

    /** Failure visible to the GAME-layer UI after an unsuccessful publication check. */
    public static String errorMessage = "";

    /** Indirilen tum ogelerin (mod + klasor dosyalari) gosterim isimleri. */
    public static List<String> downloadedNames = new ArrayList<String>();

}
