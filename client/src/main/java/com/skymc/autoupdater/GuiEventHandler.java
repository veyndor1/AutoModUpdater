package com.skymc.autoupdater;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class GuiEventHandler {
    @SubscribeEvent public void onScreenOpening(ScreenEvent.Opening event) {
        if (ClientState.promptDismissed || !(event.getNewScreen() instanceof TitleScreen)) return;
        ClientState state = ClientState.load();
        if (state != null && state.json.has("startupHandled") && state.json.get("startupHandled").getAsBoolean()) return;
        if (state != null && (state.mods() > 0 || !state.error().isEmpty())) event.setNewScreen(new GuiUpdatePrompt(state));
    }
}
