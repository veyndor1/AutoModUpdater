package com.skymc.autoupdater;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.Component;

public final class GameRestarter {
    static void restart(ClientState state) {
        boolean automatic = state.autoRestart() && state.helperReady() && state.canRelaunch();
        boolean requested = state.helperReady() && state.request("restart", automatic);
        if (automatic && requested) {
            Minecraft.getInstance().stop();
            return;
        }
        String reason = state.autoRestart()
                ? "Otomatik yeniden baslatma kullanilamiyor."
                : "Otomatik yeniden baslatma kapali.";
        Minecraft.getInstance().setScreen(new AlertScreen(() -> Minecraft.getInstance().stop(),
                Component.literal(reason),
                Component.literal("Oyun kapandiktan sonra Minecraft Launcher'dan tekrar acin."),
                Component.literal("Oyunu Kapat"), false));
    }
}
