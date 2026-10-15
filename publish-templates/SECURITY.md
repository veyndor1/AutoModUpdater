# Security

The updater validates the pack identity, config SHA256 values, file inventories,
portable paths and ZIP expansion sizes before installation. Links and junctions
are not accepted as config publication inputs or installation targets.
Changed config roots are retained as complete backups. Failed replacement batches
are rolled back; incomplete recovery stops game startup.

This repository intentionally provides public download access. Do not grant
repository write access to players. Review the selected publication inputs before
uploading: publish only distributable game settings, not account credentials.

Report updater defects to the repository owner, **veyndor1**. Include the updater
version and relevant `[AutoModUpdater]` log lines, excluding credentials.
