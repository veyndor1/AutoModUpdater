package com.skymc.autoupdater.bootstrap;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

/** Config targets and ZIP names use the same portable, link-free path rules. */
final class SafePaths {
    static void relative(String value) throws IOException {
        if (value == null || value.isEmpty() || value.contains("\\") || value.startsWith("/"))
            throw new IOException("Invalid relative path");
        for (String part : value.split("/", -1)) segment(part);
    }
    static void segment(String value) throws IOException {
        if (value == null || value.isBlank() || value.equals(".") || value.equals("..")
                || value.length() > 255 || value.endsWith(".") || value.endsWith(" ")
                || value.matches("(?s).*[\\\\/:*?\"<>|\\x00-\\x1f].*")) throw new IOException("Invalid path component");
        String stem = value.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("Reserved Windows path");
    }
    static Path resolve(Path root, String relative) throws IOException {
        relative(relative);
        Path normalized = root.toAbsolutePath().normalize();
        Path target = normalized.resolve(relative).normalize();
        if (!target.startsWith(normalized) || target.equals(normalized)) throw new IOException("Path outside root");
        checkLinks(normalized);
        Path current = normalized;
        for (Path component : normalized.relativize(target)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || !current.toRealPath().equals(current)))
                throw new IOException("Linked path is not allowed");
        }
        return target;
    }
    static void checkLinks(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path p = absolute; p != null; p = p.getParent()) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(p) || !p.toRealPath().equals(p)))
                throw new IOException("Linked root is not allowed");
        }
    }
    static void deleteOwnedTree(Path root, Path target) throws IOException {
        Path checked = resolve(root, root.toAbsolutePath().normalize().relativize(target.toAbsolutePath().normalize()).toString().replace('\\', '/'));
        if (!Files.exists(checked, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(checked)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                resolve(root, root.toAbsolutePath().normalize().relativize(p).toString().replace('\\', '/'));
                Files.delete(p);
            }
        }
    }
}
