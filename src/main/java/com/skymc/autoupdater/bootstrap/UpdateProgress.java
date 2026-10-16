package com.skymc.autoupdater.bootstrap;

/** In-memory progress contract; no Forge GAME-layer references or disk traffic. */
public interface UpdateProgress {
    enum Kind { MOD, CONFIG, REMOVAL }
    enum Phase {
        WAITING("Bekliyor"), CHECKING("Kontrol ediliyor"), DOWNLOADING("İndiriliyor"),
        VERIFYING("Doğrulanıyor"), READY("Hazır"), CURRENT("Güncel"), FAILED("Başarısız"),
        REMOVE_READY("Kaldırılacak · yedeği korunacak"), ABSENT("Zaten kaldırılmış");
        final String label;
        Phase(String label) { this.label = label; }
    }
    UpdateProgress NONE = new UpdateProgress() {};
    default void inventory(Kind kind, String id, String name) {}
    default void item(Kind kind, String id, String name, Phase phase, long bytes, long total) {}
    default void status(String text) {}
    default void removal(String id, String name, String reason) { inventory(Kind.REMOVAL, id, name); }
}
