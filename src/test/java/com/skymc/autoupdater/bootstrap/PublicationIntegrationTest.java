package com.skymc.autoupdater.bootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** The actual PowerShell publication is consumed by the Java installer, not a mirrored encoder. */
class PublicationIntegrationTest {
    @TempDir Path temp;
    @Test void outputInsideSourceIsRejectedWithoutCreatingOrOverwritingInputContent() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Path mods=temp.resolve("mods"), configs=temp.resolve("configs"); Files.createDirectories(mods); Files.createDirectories(configs);
        Path input=configs.resolve("user.toml"); Files.writeString(input,"preserve");
        for (String[] args : List.of(new String[]{"-OutDir",configs.resolve("nested-output").toString()},new String[]{"-OutDir",temp.resolve("output").toString(),"-Out",input.toString()})) {
            List<String> command=new ArrayList<>(List.of("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",Path.of("make-manifest.ps1").toAbsolutePath().toString(),"-ModsDir",mods.toString(),"-ConfigsDir",configs.toString()));
            command.addAll(Arrays.asList(args)); Process process=new ProcessBuilder(command).redirectErrorStream(true).start(); process.getInputStream().readAllBytes();
            assertTrue(process.waitFor(20,TimeUnit.SECONDS)); assertNotEquals(0,process.exitValue());
            assertEquals("preserve",Files.readString(input)); assertFalse(Files.exists(configs.resolve("nested-output")));
        }
    }
    @Test void generatedPublicationInstallsAndBacksUpExactConfigTreeAndIsStableOnSecondRun() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
        Path mods = temp.resolve("source-mods"), configs = temp.resolve("source-configs"), out = temp.resolve("publication"), game = temp.resolve("game");
        Files.createDirectories(mods); Files.writeString(mods.resolve("sample.jar"),"mod-content");
        Files.createDirectories(configs.resolve("Ömer space/nested/empty"));
        Files.writeString(configs.resolve("Ömer space/nested/.hidden"),"hidden-content");
        Files.getFileAttributeView(configs.resolve("Ömer space/nested/.hidden"),DosFileAttributeView.class).setHidden(true);
        Files.writeString(configs.resolve("root.toml"),"enabled=true");
        Files.getFileAttributeView(configs.resolve("root.toml"),DosFileAttributeView.class).setReadOnly(true);
        Files.writeString(configs.resolve("automodupdater.json"),"must not publish");
        try (ConfigFixture fixture = new ConfigFixture()) {
            Process generator = new ProcessBuilder("powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",Path.of("make-manifest.ps1").toAbsolutePath().toString(),
                    "-ModsDir",mods.toString(),"-ConfigsDir",configs.toString(),"-BaseUrl",fixture.base+"/assets","-OutDir",out.toString())
                    .redirectErrorStream(true).start();
            String log = new String(generator.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(generator.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,generator.exitValue(),log);
            fixture.manifest = Files.readString(out.resolve("server-mods.json")); new PackManifest(fixture.manifest);
            try (var files = Files.walk(out)) {
                for (Path file : files.filter(Files::isRegularFile).toList())
                    fixture.assets.put("/assets/"+out.relativize(file).toString().replace('\\','/'),Files.readAllBytes(file));
            }
            Files.createDirectories(game.resolve("config/Ömer space")); Files.writeString(game.resolve("config/Ömer space/user-file"),"backup-me");
            Files.writeString(game.resolve("config/unlisted"),"keep-me");
            String settings = "{\"manifestUrl\":\""+fixture.base+"/manifest\",\"autoRestart\":false}";
            Files.writeString(game.resolve("config/automodupdater.json"),settings);
            ModDownloader.runSync(game.toFile(),game.resolve("mods").toFile());
            assertEquals(1,UpdateState.newModsDownloaded); assertTrue(Files.exists(game.resolve("config/Ömer space/user-file")));
            PendingInstall.install(game); new ConfigInstaller(game,null).recover(true);
            assertEquals("mod-content",Files.readString(game.resolve("mods/sample.jar")));
            assertEquals("hidden-content",Files.readString(game.resolve("config/Ömer space/nested/.hidden")));
            assertTrue(Files.isDirectory(game.resolve("config/Ömer space/nested/empty")));
            assertEquals("enabled=true",Files.readString(game.resolve("config/root.toml")));
            assertEquals("keep-me",Files.readString(game.resolve("config/unlisted")));
            assertEquals(settings,Files.readString(game.resolve("config/automodupdater.json")));
            try (var backups = Files.walk(StateFiles.root(game).resolve("config-backups"))) {
                Path backup = backups.filter(p->p.getFileName().toString().equals("user-file")).findFirst().orElseThrow();
                assertEquals("backup-me",Files.readString(backup));
            }
            int count = fixture.downloads.get(); ModDownloader.runSync(game.toFile(),game.resolve("mods").toFile());
            assertEquals(count,fixture.downloads.get()); assertEquals(0,UpdateState.newModsDownloaded); assertEquals(0,UpdateState.configEntriesUpdated);
        } finally { Files.getFileAttributeView(configs.resolve("root.toml"),DosFileAttributeView.class).setReadOnly(false); }
    }
}
