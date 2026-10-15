package com.skymc.autoupdater;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(AutoUpdaterMod.MODID)
public final class AutoUpdaterMod {
    public static final String MODID = "automodupdater";
    public AutoUpdaterMod() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(new GuiEventHandler());
            FMLJavaModLoadingContext.get().getModEventBus().addListener(this::loadComplete);
        }
    }
    private void loadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(() -> {
            ClientState state = ClientState.load();
            if (state != null && state.helperReady()) state.request("install", false);
        });
    }
}
