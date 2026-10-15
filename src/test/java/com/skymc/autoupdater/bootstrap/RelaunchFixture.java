package com.skymc.autoupdater.bootstrap;
import java.nio.file.*;
public final class RelaunchFixture {
    public static void main(String[] args) throws Exception { Files.writeString(Path.of(args[0]), "launched"); }
}
