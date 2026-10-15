package updater.acceptance.extra;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Mod("acceptanceextra")
public final class ExtraProbe {
    public ExtraProbe() throws Exception {
        String version;
        try (var in = ExtraProbe.class.getResourceAsStream("/acceptance-extra-version.txt")) {
            version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        Files.writeString(FMLPaths.GAMEDIR.get().resolve("extra-loaded-version.txt"), version);
    }
}
