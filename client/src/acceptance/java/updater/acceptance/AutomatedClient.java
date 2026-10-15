package updater.acceptance;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;

/** Optional test-only UI driver; activated only in isolated profiles with automated-case.txt. */
public final class AutomatedClient {
    private int ticks, step, connectionTicks;
    private final Path game = FMLPaths.GAMEDIR.get();
    private boolean dismissed;
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null) return;
        try {
            if (++ticks > 2400) throw new IllegalStateException("Acceptance timed out");
            if (ticks < 20) return;
            String mode = Files.readString(game.resolve("automated-case.txt")).trim();
            if (step == 0) {
                step = 1;
                mc.setScreen(new TitleScreen());
                return;
            }
            boolean prompt = mc.screen != null && mc.screen.getClass().getName().equals("com.skymc.autoupdater.GuiUpdatePrompt");
            if (mode.startsWith("ui-")) {
                if (prompt) throw new IllegalStateException("Duplicate GAME-layer update prompt");
                if (!Files.readString(game.resolve("config/acceptance/settings.toml")).trim().equals("v2"))
                    throw new IllegalStateException("Updated config was not active at game startup");
                String expected = mode.equals("ui-config-only") ? "v1" : "v2";
                if (!Files.readString(game.resolve("extra-loaded-version.txt")).trim().equals(expected))
                    throw new IllegalStateException("Wrong mod version after updater decision");
                complete(mc, mode + "-active-no-prompt"); return;
            }
            if (step == 1) {
                if (mode.equals("offline")) {
                    if (!prompt) throw new IllegalStateException("Offline error screen missing");
                    if (mc.screen.children().stream().anyMatch(c -> c instanceof Button b && b.getMessage().getString().equals("Yeniden Baslat")))
                        throw new IllegalStateException("Offline error offered a restart");
                    complete(mc, "offline-error-no-restart"); return;
                }
                if (mode.equals("config-only")) {
                    if (prompt) throw new IllegalStateException("Config update prompted for restart");
                    complete(mc, mode + "-no-restart"); return;
                }
                if (mode.equals("restart") && !prompt) {
                    if (!Files.readString(game.resolve("extra-loaded-version.txt")).equals("v2"))
                        throw new IllegalStateException("Updated mod not active after restart");
                    connect(mc); step = 4; return;
                }
                if (!prompt) throw new IllegalStateException("Missing update prompt");
                String version = Files.readString(game.resolve("extra-loaded-version.txt"));
                if (!version.equals("v1")) throw new IllegalStateException("Updated mod loaded in first session");
                if (!Files.readString(game.resolve("config/acceptance/settings.toml")).trim().equals("v1"))
                    throw new IllegalStateException("New config activated with old mod");
                if (mode.equals("dismiss")) {
                    button(mc, "Ana Menuye Don").onPress();
                    dismissed = true; step = 2; return;
                }
                if (mode.equals("restart") || mode.equals("manual")) {
                    Files.writeString(game.resolve("first-session-prompt.txt"), "new-mod-inactive:" + version + ";config:v1");
                    button(mc, "Yeniden Baslat").onPress();
                    step = mode.equals("manual") ? 3 : 5;
                    return;
                }
                throw new IllegalStateException("Unknown acceptance mode");
            }
            if (step == 2) {
                if (!(mc.screen instanceof TitleScreen) || !dismissed) throw new IllegalStateException("Dismiss failed");
                mc.setScreen(new TitleScreen());
                if (!(mc.screen instanceof TitleScreen)) throw new IllegalStateException("Prompt repeated after dismissal");
                complete(mc, "dismiss-and-normal-exit");
            } else if (step == 3) {
                if (!(mc.screen instanceof AlertScreen)) throw new IllegalStateException("Manual restart alert missing");
                Files.writeString(game.resolve("automated-result.txt"), "manual-exit");
                button(mc, "Oyunu Kapat").onPress();
            } else if (step == 4) {
                if (mc.level != null && mc.player != null && mc.getConnection() != null) {
                    if (++connectionTicks > 40) {
                        Files.writeString(game.resolve("server-joined.txt"), "connected-with-client-only-updater");
                        mc.getConnection().getConnection().disconnect(Component.literal("Acceptance finished"));
                        complete(mc, "restart-mod-active-and-server-joined");
                    }
                } else if (mc.screen instanceof DisconnectedScreen) throw new IllegalStateException("Server handshake failed");
            }
        } catch (Exception e) {
            try { Files.writeString(game.resolve("automated-error.txt"), e.toString()); } catch (Exception ignored) { }
            mc.stop();
        }
    }
    private Button button(Minecraft mc, String text) {
        return mc.screen.children().stream().filter(c -> c instanceof Button b && b.getMessage().getString().equals(text))
                .map(c -> (Button)c).findFirst().orElseThrow(() -> new IllegalStateException("Missing button: " + text));
    }
    private void connect(Minecraft mc) {
        ServerData server = new ServerData("Updater acceptance", "127.0.0.1:25566", false);
        ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(server.ip), server, false);
    }
    private void complete(Minecraft mc, String result) throws Exception {
        if (!result.equals("offline-error-no-restart") && !Files.exists(game.resolve("probe-mixin.txt")))
            throw new IllegalStateException("Title screen Mixin did not run");
        Files.writeString(game.resolve("automated-result.txt"), result);
        mc.stop();
    }
}
