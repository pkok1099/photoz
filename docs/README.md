# PhotoZ — Project Documentation

> **Version**: Post-audit-b2-fixes (commit `9c24a401`)
> **Last updated**: 2026-07-09
> **Auditor**: Super Z (automated deep audit + fixes)

---

## Dokumentasi Index

| Dokumen | Deskripsi |
|---------|-----------|
| [01-architecture.md](01-architecture.md) | Arsitektur aplikasi, module layout, DI (Hilt), navigation |
| [02-rclone-integration.md](02-rclone-integration.md) | Integrasi rclone via gomobile JNI — semua RC operations, config, error handling |
| [03-encryption.md](03-encryption.md) | Sistem enkripsi: CBC/GCM/chunked-GCM, Argon2id, SQLCipher, key escrow |
| [04-backup-restore.md](04-backup-restore.md) | Backup/restore V1-V5 format, metadata round-trip, vault protection restore |
| [05-sync-workflow.md](05-sync-workflow.md) | Cloud sync workflow: PhotoSyncWorker, HashRegistry dedup, multi-vault |
| [06-database.md](06-database.md) | Room database schema v17, migrations, SQLCipher, BootstrapDatabase |
| [07-testing.md](07-testing.md) | Test strategy, coverage, cara run tests, test patterns |
| [08-build-and-deploy.md](08-build-and-deploy.md) | Build instructions, flavors (play/foss), CI/CD, release process |
| [09-audit-summary.md](09-audit-summary.md) | Summary audit B2 + fix yang diterapkan (72 findings, 67 tests) |
| [10-troubleshooting.md](10-troubleshooting.md) | Common issues, debug tips, log locations, FAQ |

---

## Quick Links

- **Audit report lengkap**: [`/download/AUDIT_B2.md`](../download/AUDIT_B2.md) (72 findings: 9 Critical, 23 High, 27 Medium, 13 Low)
- **Audit fix branch**: `audit-b2-fixes` (8 commits di atas B2)
- **TODO tracking**: [`TODO_SYNC.md`](../TODO_SYNC.md), [`TODO1.md`](../TODO1.md), [`ROADMAP2.md`](../ROADMAP2.md)

---

## Overview Aplikasi

PhotoZ adalah Android photo vault dengan fitur:

- **On-device encryption** — AES-256 (CBC untuk media, GCM untuk metadata, chunked-GCM untuk video streaming)
- **Cloud sync** — mirror ke cloud storage sendiri via rclone (gomobile JNI, no subprocess)
- **Multi-vault** — satu app bisa punya multiple vault dengan password berbeda
- **Two-layer key escrow** — recovery phrase + password wrap VMK untuk fresh-install recovery
- **Content-hash dedup** — foto dengan konten identik hanya upload sekali
- **SQLCipher at-rest encryption** — DB foto diencrypt dengan key dari Android Keystore

### Tech Stack

| Layer | Teknologi |
|-------|-----------|
| Language | Kotlin 2.4.0 |
| Build | Gradle 9.6.1, AGP 9.1.0 |
| UI | Jetpack Compose (Material3) + legacy DataBinding |
| DI | Hilt / Dagger 2.60 |
| DB | Room 2.8.4 + SQLCipher 4.9.0 |
| Crypto | JCA + Bouncy Castle 1.84 (Argon2id) |
| Sync | rclone 1.68.2 via gomobile JNI (libgojni.so) |
| Background | WorkManager 2.11.2 (foreground service for sync) |
| Image loading | Coil 2.7.0 (custom EncryptedImageFetcher) |
| Video | Media3 / ExoPlayer 1.10.1 |

### Min SDK / Target

- `minSdk = 35` (Android 15)
- `targetSdk = 36` (Android 16)
- `compileSdk = 37`
- ABI: `arm64-v8a` only (rclone gomobile AAR)

---

## Branch Strategy

| Branch | Purpose |
|--------|---------|
| `main` | Upstream Photok (leonlatsch/Photok) + fork customizations |
| `B2` | Branch audit target — rclone gomobile JNI migration + SQLCipher + Argon2id |
| `audit-b2-fixes` | **Branch hasil audit** — 8 commits dengan fix Critical/High/Medium + 67 tests |

Untuk detail setiap commit di `audit-b2-fixes`, lihat [09-audit-summary.md](09-audit-summary.md).
