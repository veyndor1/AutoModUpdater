package com.skymc.autoupdater.bootstrap;

import com.google.gson.JsonObject;
import java.nio.file.Path;

/** The loader thread owns transactions and decisions; Swing never owns game shutdown. */
final class StartupCoordinator {
    static JsonObject run(Path game, Path mods) throws Exception {
        ProgressWindow window = new ProgressWindow(game.toFile());
        window.open();
        JsonObject state = null;
        try {
            while (true) {
                window.begin();
                UpdateReport report;
                try { report = ModDownloader.download(game.toFile(), mods.toFile(), window); }
                catch (IllegalStateException fatal) {
                    window.awaitError("Config kurtarma tamamlanamadı. Minecraft'ı yeniden açarak tekrar deneyin.", false);
                    exit(window); return null;
                }
                if (!window.available()) {
                    state = StateFiles.snapshot(game);
                    StateFiles.write(StateFiles.session(game, ProcessHandle.current().pid()), state);
                    if (PendingInstall.hasPending(game) || SelfUpdate.hasPending(game)) HelperLauncher.start(game, state);
                    if (report.removals()>0) {
                        HelperLauncher.requestRestart(game, state, false);
                        exit(window); return null;
                    }
                    return state; // Original GAME-layer prompt is the UI-unavailable fallback.
                }
                if (!report.successful()) {
                    if (window.awaitError(report.error(), true) == ProgressWindow.Choice.RETRY) continue;
                    exit(window); return null;
                }
                if (state == null) state = StateFiles.snapshot(game);
                StateFiles.write(StateFiles.session(game, ProcessHandle.current().pid()), state);
                boolean pending = PendingInstall.hasPending(game) || SelfUpdate.hasPending(game);
                if (pending && !state.get("helperReady").getAsBoolean()) {
                    boolean prepared = false;
                    while (!prepared) {
                        try { HelperLauncher.start(game, state); prepared = true; }
                        catch (Exception helperError) {
                            if (window.awaitError("Kurulum yardımcısı hazırlanamadı. " + helperError.getClass().getSimpleName(), true)
                                    == ProgressWindow.Choice.EXIT) { exit(window); return null; }
                        }
                    }
                }
                boolean automatic = pending && Config.autoRestart && state.has("canRelaunch") && state.get("canRelaunch").getAsBoolean();
                ProgressWindow.Choice choice = report.changed() ? window.awaitSuccess(report, pending, automatic) : window.currentAndContinue();
                if (pending) {
                    // Write before exit; retry in this window if the request cannot be committed.
                    while (true) {
                        try { HelperLauncher.requestRestart(game, state, choice == ProgressWindow.Choice.CONTINUE && automatic); break; }
                        catch (Exception requestError) {
                            if (window.awaitError("Açılış isteği kaydedilemedi. Tekrar deneyin.", true) == ProgressWindow.Choice.EXIT) {
                                exit(window); return null;
                            }
                        }
                    }
                }
                if (choice == ProgressWindow.Choice.EXIT || pending) { exit(window); return null; }
                state.addProperty("startupHandled", true);
                StateFiles.write(StateFiles.session(game, ProcessHandle.current().pid()), state);
                return state;
            }
        } finally { window.close(); }
    }
    private static void exit(ProgressWindow window) { window.close(); System.exit(0); }
}
