# Security

## Supported distribution

The current player distribution is AutoModUpdater 1 for Minecraft 1.20.1,
Forge 47.4.10 and Java 17. Download the complete JAR from the pack owner's release.
Do not install a bootstrap-only or embedded-client-only artifact.

## Reporting a vulnerability

Contact the repository owner, **veyndor1**, through a private GitHub vulnerability
report when that feature is enabled. Otherwise request a private contact channel
through the owner's GitHub profile before sharing sensitive details.
Do not post access tokens, launcher arguments, account files, worlds or full logs.
Include the updater version and a minimal redacted reproduction.

## Installation protections

- Self-update requires HTTPS, SHA-256, expected size and compatible version metadata.
- The complete outer JAR and its embedded client are checked before installation.
- Configuration archives have SHA-256, inventory, path and size checks.
- Symbolic links, junctions, path traversal and conflicting file names are rejected.
- Updates are staged, and changed published configuration roots are backed up.
- Mod and updater replacements wait until the game exits; a profile lock prevents
  competing installers. Journals support recovery after interrupted replacement.
- Authentication arguments used for restart are kept in memory.

## Trust boundaries and limits

Only trusted owners should have write access to a pack publication. Published mod
JARs execute code with the player's permissions. Hashes supplied by the same
publication detect corruption; they do not protect against an attacker who can
replace both the manifest and its artifacts. There is no independent signing key.

Legacy mod entries can omit SHA-1, and the general pack downloader accepts HTTP
for compatibility. Use HTTPS with verified hashes for every real publication.
Self-update has its own stricter HTTPS and SHA-256 checks. ProGuard raises the
effort needed to inspect bytecode; it does not make reverse engineering impossible.

Never publish local credentials or account data. Keep source access and ProGuard
mapping files private. Third-party mod publishers remain responsible for the
security and compatibility of their own binaries.
