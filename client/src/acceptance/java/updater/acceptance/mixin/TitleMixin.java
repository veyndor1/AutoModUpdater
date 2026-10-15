package updater.acceptance.mixin;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.file.Files;

@Mixin(TitleScreen.class)
public abstract class TitleMixin {
    @Inject(method="m_7856_", at=@At("HEAD"), remap=false)
    private void recordTitle(CallbackInfo callback) throws Exception {
        Files.writeString(FMLPaths.GAMEDIR.get().resolve("probe-mixin.txt"), "mixin-applied");
    }
}
