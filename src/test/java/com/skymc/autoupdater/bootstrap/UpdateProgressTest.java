package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateProgressTest {
    @TempDir Path game;
    @Test void reportsModsAndConfigTransfersOnlyReadyAfterVerification() throws Exception {
        try (ConfigFixture server = new ConfigFixture()) {
            Files.createDirectories(game.resolve("config"));
            Files.writeString(game.resolve("config/automodupdater.json"), "{\"manifestUrl\":\""+server.base+"/manifest\"}");
            server.publish(ConfigFixture.array(server.mod("sample.jar","new")),ConfigFixture.array(server.file("sample.toml","new")));
            List<String> events=new ArrayList<>();
            UpdateProgress sink=new UpdateProgress() {
                @Override public void inventory(Kind kind,String id,String name) { events.add(kind+":inventory"); }
                @Override public void item(Kind kind,String id,String name,Phase phase,long bytes,long total) { events.add(kind+":"+phase); if(bytes>0) events.add(kind+":bytes"); }
            };
            UpdateReport report=ModDownloader.download(game.toFile(),game.resolve("mods").toFile(),sink);
            assertTrue(report.successful()); assertEquals(1,report.mods()); assertEquals(1,report.configs());
            for(String kind:List.of("MOD","CONFIG")) {
                assertTrue(events.contains(kind+":inventory")); assertTrue(events.contains(kind+":bytes"));
                assertTrue(events.indexOf(kind+":VERIFYING")<events.indexOf(kind+":READY"));
            }
            assertTrue(PendingInstall.hasPending(game)); assertFalse(Files.exists(game.resolve("mods/sample.jar")));
            assertFalse(Files.exists(game.resolve("config/sample.toml")),"Config must stay paired with staged mod");
        }
    }
    @Test void failedReleaseDoesNotReportSuccessfulChanges() throws Exception {
        try(ConfigFixture server=new ConfigFixture()) {
            Files.createDirectories(game.resolve("config"));
            Files.writeString(game.resolve("config/automodupdater.json"),"{\"manifestUrl\":\""+server.base+"/manifest\"}");
            JsonObject bad=server.mod("sample.jar","new"); bad.addProperty("sha1","0".repeat(40));
            server.publish(ConfigFixture.array(bad),new JsonArray());
            UpdateReport report=ModDownloader.download(game.toFile(),game.resolve("mods").toFile(),UpdateProgress.NONE);
            assertFalse(report.successful()); assertFalse(report.changed()); assertFalse(PendingInstall.hasPending(game));
        }
    }
    @Test void presentationPreferencesPreserveUnknownFieldsAndRefuseMalformedData() throws Exception {
        Files.createDirectories(game.resolve("config")); Path settings=game.resolve("config/automodupdater.json");
        Files.writeString(settings,"{\"manifestUrl\":\"https://example.test/manifest\",\"autoRestart\":false,\"custom\":{\"value\":7}}");
        Config.savePresentation(game.toFile(),false,true); Config.load(game.toFile());
        JsonObject data=StateFiles.read(settings); assertEquals(7,data.getAsJsonObject("custom").get("value").getAsInt());
        assertEquals("https://example.test/manifest",Config.manifestUrl); assertFalse(Config.autoRestart);
        assertFalse(Config.nightTheme); assertTrue(Config.reducedMotion);
        Files.writeString(settings,"broken"); assertThrows(java.io.IOException.class,()->Config.savePresentation(game.toFile(),true,false));
        assertEquals("broken",Files.readString(settings));
    }
    @Test void bundledBannerTimingAndTurkishTypefaceAreUsable() throws Exception {
        for(String name:List.of("day.gif","night.gif")) {
            UiAssets.Clip clip=UiAssets.load(name); assertTrue(clip.frames().length>1); assertTrue(clip.duration()>0);
            assertSame(clip.frames()[0],clip.frame(500,true)); assertSame(clip.frame(10,false),clip.frame(10+clip.duration(),false));
            var still=UiAssets.still(name.replace(".gif",".png")).frames()[0];
            var encoded=clip.frames()[0]; long difference=0; int samples=0;
            for(int y=0;y<still.getHeight();y+=3) for(int x=0;x<still.getWidth();x+=3) {
                int a=still.getRGB(x,y),b=encoded.getRGB(x,y);
                for(int shift:new int[]{0,8,16}) difference+=Math.abs(((a>>shift)&255)-((b>>shift)&255));
                samples+=3;
            }
            assertTrue(difference/(double)samples<12,"GIF colors must preserve the source scene palette: "+name);
        }
        assertEquals(-1,UiAssets.font(false,15).canDisplayUpTo("Çıkış İndiriliyor Güncel şğüöı"));
        assertEquals(-1,UiAssets.font(true,15).canDisplayUpTo("Çıkış İndiriliyor Güncel şğüöı"));
    }
}
