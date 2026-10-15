# Code protection

The owner has explicitly chosen to keep this source repository public. The
proprietary license still reserves modification, redistribution and resale rights
for the owner's code. Public releases contain the compiled and obfuscated
distribution, its license and third-party notices for the owner's `mcmods2` pack.

## Release pipeline

1. Compile bootstrap and client Java code with Java 17.
2. Relocate the bundled Gson package to isolate it from other mods.
3. Apply the project's existing ProGuard 7.6.1 pipeline to both Java modules.
4. Keep Forge entry points, service registration and required integration classes
   compatible with the loader. The existing rules retain selected GUI classes too.
5. Combine bootstrap, helper, UI assets and the embedded client into one outer JAR.
6. Verify the release structure and embedded client version with the release tests.
7. Publish the complete JAR with SHA-256 checksums and the proprietary license.

The two ProGuard mapping files remain local and are excluded from Git. Build
caches, launcher profiles, account data, internal notes and machine-specific test
evidence are excluded from the repository and release artifacts.

## Limits

Java bytecode can be decompiled, and required integration classes retain readable
names. Obfuscation makes inspection harder; it is not encryption or copy-proofing.
It does not conceal the source available in this public repository.
SHA-256 checksums detect changed downloads when compared with a trusted value;
they are not digital signatures or a substitute for trusted publication access.

The proprietary license reserves the owner's source, modification, resale and
redistribution rights while allowing authorized use of the official player binary.
Third-party components retain their original licenses and notices.
