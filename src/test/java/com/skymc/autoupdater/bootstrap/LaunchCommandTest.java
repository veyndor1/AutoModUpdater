package com.skymc.autoupdater.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LaunchCommandTest {
    @TempDir Path dir;
    @Test void parsesJavaArgumentFilesWithoutLosingQuotesSpacesOrEmptyValues() throws Exception {
        assertEquals(List.of("-Dkey=a b", "main.Class", "--name", "Ömer", "", "ab"),
                LaunchCommand.parseArgumentFile("#comment\n-Dkey=\"a b\" main.Class --name 'Ömer' \"\" \"a\\\n  b\""));
    }
    @Test void snapshotsArgumentFilesBeforeLauncherMayDeleteThem() throws Exception {
        Files.writeString(dir.resolve("args.txt"), "--module-path \"a b\" --launchTarget forgeclient");
        assertEquals(List.of("java", "--module-path", "a b", "--launchTarget", "forgeclient", "--token", "example"),
                LaunchCommand.expandArgumentFiles(List.of("java", "@args.txt", "--token", "example"), dir));
    }
    @Test void preservesDisabledAndEscapedArgumentFiles() throws Exception {
        assertEquals(List.of("java", "@@literal", "--disable-@files", "@missing"),
                LaunchCommand.expandArgumentFiles(List.of("java", "@@literal", "--disable-@files", "@missing"), dir));
    }
}
