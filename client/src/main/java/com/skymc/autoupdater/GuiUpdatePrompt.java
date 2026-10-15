package com.skymc.autoupdater;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import java.util.List;

public final class GuiUpdatePrompt extends Screen {
    private final ClientState state;
    GuiUpdatePrompt(ClientState state) {
        super(Component.literal("Auto Mod Updater"));
        this.state = state;
    }
    @Override protected void init() {
        int cx = width / 2, by = height / 2 + 24;
        if (state.error().isEmpty()) {
            addRenderableWidget(Button.builder(Component.literal("Yeniden Baslat"), b -> GameRestarter.restart(state))
                    .bounds(cx - 155, by, 150, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Ana Menuye Don"), b -> {
            ClientState.promptDismissed = true;
            minecraft.setScreen(new TitleScreen());
        }).bounds(state.error().isEmpty() ? cx + 5 : cx - 75, by, 150, 20).build());
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        int cx = width / 2, y = height / 2 - 70;
        graphics.drawCenteredString(font, "Auto Mod Updater", cx, y, 0xFFFFFF);
        if (!state.error().isEmpty()) {
            graphics.drawCenteredString(font, "Guncelleme basarisiz; mevcut dosyalar korundu.", cx, y + 18, 0xFF7777);
            String reason = state.error();
            if (reason.length() > 82) reason = reason.substring(0, 79) + "...";
            graphics.drawCenteredString(font, reason, cx, y + 34, 0xCCCCCC);
            graphics.drawCenteredString(font, "Ayrinti icin latest.log dosyasini kontrol edin.", cx, y + 52, 0xCCCCCC);
            super.render(graphics, mouseX, mouseY, partialTicks);
            return;
        }
        graphics.drawCenteredString(font, state.mods() + " yeni mod indirildi.", cx, y + 18, 0xFFFF55);
        graphics.drawCenteredString(font, "Modlarin etkinlesmesi icin Minecraft yeniden baslatilmali.", cx, y + 34, 0xCCCCCC);
        graphics.drawCenteredString(font, "Made by veyndor", cx, y + 52, 0x555A65);
        List<String> names = state.names();
        if (!names.isEmpty()) {
            int shown = Math.min(names.size(), 3);
            String text = String.join(", ", names.subList(0, shown));
            if (names.size() > shown) text += " (+" + (names.size() - shown) + ")";
            graphics.drawCenteredString(font, text, cx, y + 70, 0x77DD77);
        }
        super.render(graphics, mouseX, mouseY, partialTicks);
    }
    @Override public void onClose() {
        ClientState.promptDismissed = true;
        minecraft.setScreen(new TitleScreen());
    }
    @Override public boolean isPauseScreen() { return true; }
}
