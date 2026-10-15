package updater.acceptance;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.ModList;
import updater.acceptance.library.ProbeLibrary;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import net.minecraftforge.common.MinecraftForge;

@Mod("acceptanceprobe")
public final class AcceptanceProbe {
    public AcceptanceProbe() throws Exception {
        Path game = FMLPaths.GAMEDIR.get();
        if (Files.exists(game.resolve("self-update-expected-version.txt"))) {
            String updaterVersion = ModList.get().getModContainerById("automodupdater").orElseThrow().getModInfo().getVersion().toString();
            if (!updaterVersion.equals(Files.readString(game.resolve("self-update-expected-version.txt")).trim()))
                throw new IllegalStateException("Wrong updater version loaded by Forge: " + updaterVersion);
            Files.writeString(game.resolve("self-update-loaded-version.txt"), updaterVersion);
        }
        String mode = Files.readString(game.resolve("automated-case.txt")).trim();
        String version = Files.readString(ModList.get().getModFileById("acceptanceextra").getFile().findResource("acceptance-extra-version.txt")).trim();
        String expected = mode.equals("config-only") || mode.equals("ui-config-only") ? "v2" : version;
        boolean earlyConfig = Files.readString(game.resolve("config/acceptance/settings.toml")).trim().equals(expected);
        boolean atApplied = Modifier.isPublic(Class.forName("net.minecraft.client.gui.screens.TitleScreen")
                .getDeclaredField("f_96717_").getModifiers());
        if (!earlyConfig || !atApplied) throw new IllegalStateException("Mod/config pairing or access transformer failed");
        Files.writeString(game.resolve("probe-loaded.json"), "{\"earlyConfig\":true,\"configVersion\":\"" + expected + "\",\"accessTransformer\":true,\"library\":\"" + ProbeLibrary.value() + "\"}");
        if (Files.exists(game.resolve("automated-case.txt"))) MinecraftForge.EVENT_BUS.register(new AutomatedClient());
    }
}
