# Repository settings

Verified on 16 October 2026.

- Repository: `veyndor1/AutoModUpdater`, private.
- Default and initially published branch: `1.20.1`.
- The owner `veyndor1` is the only collaborator with write access. Other users cannot
  push or create branches without an explicit access grant.
- Force-push and branch deletion protection were requested, including enforcement
  for the owner. GitHub rejected the protection request with HTTP 403 because this
  private repository requires GitHub Pro for that feature. Rulesets are unavailable
  for the same reason. These protections are **not enabled**.
- Owner-only access is an access control, not a replacement for branch protection.
  After a suitable plan is available, enable protection for `1.20.1`, prohibit
  force-push and deletion, and keep direct owner commits permitted as requested.
- GitHub vulnerability alerts are enabled. This does not imply that code scanning
  or every paid security feature is enabled.
- Releases contain compiled, obfuscated complete updater builds and companion
  licenses/checksums. Public copies are distributed from `veyndor1/mcmods2`.
- Commits use the owner's GitHub noreply identity. No additional contributors or
  assistant co-authors are added by the publication process.

The related `mcmods2` and `mc-mods` repositories retain their existing default
branches, pack manifests and access settings. Publication only added README links
and the updater release; it did not change the active pack manifest.
