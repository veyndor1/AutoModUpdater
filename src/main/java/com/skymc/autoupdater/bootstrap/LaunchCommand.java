package com.skymc.autoupdater.bootstrap;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Capture the actual launcher command; never reconstruct authentication/Forge arguments. */
public final class LaunchCommand {
    public static List<String> capture() throws Exception {
        ProcessHandle.Info info = ProcessHandle.current().info();
        List<String> result;
        if (info.command().isPresent() && info.arguments().isPresent()) {
            result = new ArrayList<>();
            result.add(info.command().get());
            result.addAll(Arrays.asList(info.arguments().get()));
        } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            String script;
            try (InputStream in = LaunchCommand.class.getResourceAsStream("/windows-command.ps1")) {
                if (in == null) throw new IOException("Windows komut okuyucusu bulunamadi");
                script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            script = script.replace("__PROCESS_ID__", Long.toString(ProcessHandle.current().pid()));
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return p.getInputStream().readAllBytes(); }
                catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("Komut okuma zaman asimi"); }
            if (p.exitValue() != 0) throw new IOException("Windows komutu okunamadi");
            JsonArray argv = JsonParser.parseString(new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8)
                    .replace("\uFEFF", "")).getAsJsonArray();
            result = new ArrayList<>();
            argv.forEach(a -> result.add(a.getAsString()));
        } else throw new IOException("Launcher komutu mevcut degil");
        if (result.size() < 2 || !Files.isRegularFile(Path.of(result.get(0)))) throw new IOException("Eksik launcher komutu");
        return expandArgumentFiles(result, Path.of(System.getProperty("user.dir")));
    }
    static List<String> expandArgumentFiles(List<String> command, Path cwd) throws IOException {
        List<String> expanded = new ArrayList<>();
        expanded.add(command.get(0));
        boolean disabled = false;
        for (int i = 1; i < command.size(); i++) {
            String arg = command.get(i);
            if (arg.equals("--disable-@files")) disabled = true;
            if (!disabled && arg.startsWith("@") && !arg.startsWith("@@")) {
                Path file = cwd.resolve(arg.substring(1)).normalize();
                expanded.addAll(parseArgumentFile(Files.readString(file, StandardCharsets.UTF_8)));
            } else expanded.add(arg);
        }
        return expanded;
    }
    /** Java @file quoting, escapes inside quotes, comments and line continuation. */
    static List<String> parseArgumentFile(String input) throws IOException {
        List<String> args = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote == 0 && c == '#') {
                while (i < input.length() && input.charAt(i) != '\n') i++;
                c = '\n';
            }
            if (quote != 0 && c == '\\' && i + 1 < input.length()) {
                char escaped = input.charAt(++i);
                if (escaped == '\n' || escaped == '\r') {
                    if (escaped == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                    while (i + 1 < input.length() && (input.charAt(i + 1) == ' ' || input.charAt(i + 1) == '\t')) i++;
                } else token.append(switch (escaped) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'f' -> '\f'; default -> escaped; });
            } else if (c == '"' || c == '\'') {
                if (quote == 0) { quote = c; started = true; }
                else if (quote == c) quote = 0;
                else token.append(c);
            } else if (quote == 0 && Character.isWhitespace(c)) {
                if (started) { args.add(token.toString()); token.setLength(0); started = false; }
            } else { token.append(c); started = true; }
        }
        if (quote != 0) throw new IOException("Eksik @file tirnagi");
        if (started) args.add(token.toString());
        return args;
    }
}
