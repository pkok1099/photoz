![PhotoZ](fastlane/metadata/android/en-US/images/featureGraphic.jpg)

# PhotoZ

Private photo vault for Android with strong on-device encryption and optional
end-to-end encrypted cloud sync via rclone.

[![Audit Status](https://img.shields.io/badge/Audit-B2%20%2B%20Fixes-brightgreen)](docs/09-audit-summary.md)
[![Tests](https://img.shields.io/badge/Tests-67%20passing-brightgreen)](docs/07-testing.md)
[![Build](https://img.shields.io/badge/Build-AGP%209.1%20%2B%20Kotlin%202.4-blue)](docs/08-build-and-deploy.md)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

## About

PhotoZ is a secure private photo vault for Android that helps you hide photos
and videos using strong AES-256 encryption. It protects sensitive media in an
encrypted gallery and keeps your private memories safe on your own device.

All files are encrypted locally with AES-256 (CBC for media bodies, GCM for
metadata, chunked-GCM for video streaming) and only decrypted in memory while
you use the app. The cloud-sync feature (optional) mirrors the encrypted
artifacts to **your own** rclone remote — PhotoZ never sees your photos,
your password, or your recovery phrase.

PhotoZ is open source, ad-free, and built with a privacy-first philosophy.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" width="24%" />
</p>

## Documentation

Full documentation available in [`docs/`](docs/README.md):

| Doc | Description |
|-----|-------------|
| [01-architecture.md](docs/01-architecture.md) | App architecture, module layout, DI, navigation |
| [02-rclone-integration.md](docs/02-rclone-integration.md) | Rclone gomobile JNI — all RC operations |
| [03-encryption.md](docs/03-encryption.md) | CBC/GCM/chunked-GCM, Argon2id, SQLCipher, key escrow |
| [04-backup-restore.md](docs/04-backup-restore.md) | Backup V1-V5 format, metadata round-trip |
| [05-sync-workflow.md](docs/05-sync-workflow.md) | Cloud sync, dedup, multi-vault |
| [06-database.md](docs/06-database.md) | Room schema v17, migrations, SQLCipher |
| [07-testing.md](docs/07-testing.md) | Test strategy, 67 tests, patterns |
| [08-build-and-deploy.md](docs/08-build-and-deploy.md) | Build instructions, CI/CD, release |
| [09-audit-summary.md](docs/09-audit-summary.md) | Audit B2 summary (72 findings, 59 fixed) |
| [10-troubleshooting.md](docs/10-troubleshooting.md) | Common issues, debug tips, FAQ |

## Download

PhotoZ is distributed as APKs from GitHub releases and sideloaded directly.

[<img src="https://raw.githubusercontent.com/andOTP/andOTP/master/assets/badges/get-it-on-github.png"
      alt="Get it on GitHub"
      height="80">](https://github.com/pkok1099/photok-sync-fork/releases/latest)

## Features

### Core vault

- Import photos and videos from your gallery
- Organize private media into albums
- Export files anytime
- Create and restore encrypted local backups (zip)
- Unlock your vault with fingerprint authentication
- Share media directly to the vault
- Option to hide the app icon (stealth dialer)
- Automatically delete original files after import

### Encryption (AES-256)

- **AES-CBC** for media bodies (v2 format)
- **AES-GCM** for metadata (v3 format, single-stream)
- **Chunked AES-GCM** for video streaming (v4 format — random access + per-chunk integrity)
- **Argon2id** KDF for new vaults (memory-hard, 2025 standard; PBKDF2 for legacy)
- **SQLCipher** at-rest DB encryption (key backed by Android Keystore)
- **Two-layer key escrow** for fresh-install recovery (phrase + password wrap VMK)

### Cloud sync (optional, rclone-backed)

PhotoZ can mirror your encrypted photos to **your own** cloud storage via
[rclone](https://rclone.org). The sync feature is end-to-end encrypted: only
the encrypted artifacts ever leave your device.

- **Encrypted artifacts on the remote** — originals, thumbnails, and video
  previews are AES-encrypted with the Vault Master Key (VMK) before upload
- **Content-hash dedup** — SHA-256 at import time; same content skips upload
- **Encrypted dedup registry** — `registry.json.crypt` (AES-256-GCM with VMK)
- **Packed thumbnails** — ≤5 MB packs for efficient upload
- **Two-layer key escrow** — fresh-install recovery via password or phrase
- **Restore from cloud** — re-download missing thumbnails/originals
- **Drive-style notifications** — foreground service with batch progress
- **Full rclone operation support** — mkdir, move, copydir, rmdir, purge,
  hash (in addition to upload/download/list/delete)

### Multi-vault

- One app, multiple vaults with distinct passwords
- Each vault has its own VMK — password "duress" opens decoy vault
- Vault ID scoped queries (no cross-vault data leakage)
- Multi-vault dedup registry (optimistic concurrency for concurrent uploads)

### Privacy-focused design

PhotoZ is designed for people who want real control over their private photos
and videos. Encryption happens on your device, and imported files are only
decrypted in memory while the app is in use. The cloud sync uses your own
rclone remote — PhotoZ has no server component and never sees your photos,
your password, or your recovery phrase.

## Translations
<!-- BEGIN-TRANSLATIONS -->
![English](https://img.shields.io/badge/English-100%25-brightgreen)
![Arabic](https://img.shields.io/badge/Arabic-48%25-red)
![Chinese (China)](https://img.shields.io/badge/Chinese%20(China)-77%25-yellow)
![Dutch](https://img.shields.io/badge/Dutch-48%25-red)
![French](https://img.shields.io/badge/French-77%25-yellow)
![German](https://img.shields.io/badge/German-88%25-yellow)
![Indonesian](https://img.shields.io/badge/Indonesian-77%25-yellow)
![Italian](https://img.shields.io/badge/Italian-67%25-orange)
![Portuguese (Brazil)](https://img.shields.io/badge/Portuguese%20(Brazil)-77%25-yellow)
![Russian](https://img.shields.io/badge/Russian-55%25-orange)
![Spanish](https://img.shields.io/badge/Spanish-57%25-orange)
![Turkish](https://img.shields.io/badge/Turkish-88%25-yellow)
![Urdu (India)](https://img.shields.io/badge/Urdu%20(India)-71%25-orange)
<!-- END-TRANSLATIONS -->

> You want to help translating PhotoZ? See [CONTRIBUTING](CONTRIBUTING.md#Translations)

## Differences Between Play and FOSS

PhotoZ is distributed in two variants: **Google Play** and **FOSS** (F-Droid, GitHub, IzzyOnDroid).
The core app is identical, but some features differ due to platform requirements and privacy expectations.

| Feature | Google Play | FOSS |
|---|---|---|
| **Telemetry** | Enabled by default (can be disabled) | Disabled by default (can be enabled) |
| **Telemetry opt-in prompt** | No prompt shown | Shown once after first unlock |
| **In-App Review** | Requested after usage milestones | Not available |

## Build

PhotoZ is a standard Android Gradle project. Build variants:

- `play` — Google Play release (includes TelemetryDeck)
- `foss` — F-Droid / sideload release (no telemetry)

```bash
# Debug build (FOSS flavor)
./gradlew :app:assembleFossDebug

# Release build (Play flavor)
./gradlew :app:assemblePlayRelease

# Run unit tests (67 tests)
./gradlew :app:testFossDebugUnitTest
```

The cloud-sync feature uses rclone as a **gomobile JNI shared library** (loaded
via `dlopen` from `app/libs/librclone.aar`). No subprocess is spawned — this
is W^X-safe on Android 16. See [docs/02-rclone-integration.md](docs/02-rclone-integration.md)
for details.

### Prerequisites

- JDK 21+
- Android SDK Platform 37 + Build-Tools 37.0.0
- Gradle 9.6.1 (via wrapper)
- AGP 9.1.0 + Kotlin 2.4.0

See [docs/08-build-and-deploy.md](docs/08-build-and-deploy.md) for full setup instructions.

### Database

PhotoZ uses two Room databases:

- **`photok.db`** (SQLCipher-encrypted, schema v17) — photos, albums, sort,
  hash_registry. Key backed by Android Keystore.
- **`photok_meta.db`** (plaintext, BootstrapDatabase) — `vault_protection`
  table (wrapped VMKs + KDF params). Readable before encrypted DB unlock.

See [docs/06-database.md](docs/06-database.md) for schema details + migration history.

## Audit Status

Branch `audit-b2-fixes` contains a comprehensive audit of the B2 migration
(rclone gomobile JNI + SQLCipher + Argon2id + chunked GCM + two-layer escrow).

| Metric | Value |
|--------|-------|
| Total findings | 72 |
| Critical (fixed) | 7/9 |
| High (fixed) | 19/23 |
| Medium (fixed) | 22/27 |
| Low (fixed) | 11/13 |
| Unit tests | 67 (0 failures) |
| Files changed | 78 |
| Insertions | 10,185 |
| Deletions | 8,088 |

See [docs/09-audit-summary.md](docs/09-audit-summary.md) for the full summary,
or [`download/AUDIT_B2.md`](../../download/AUDIT_B2.md) for the detailed report.

## Community

### Contributors

<a href="https://github.com/pkok1099/photok-sync-fork/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=pkok1099/photok-sync-fork"  alt="PhotoZ Contributors"/>
</a>

## Related Tools

[![RecWare-Photok](https://github-readme-stats.vercel.app/api/pin/?username=Blk-S-Bellamy&repo=RecWare-Photok&show_owner=true&theme=transparent)](https://github.com/Blk-S-Bellamy/RecWare-Photok)

LICENSE
=======

    Copyright 2020-2026 Leon Latsch / PhotoZ contributors

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

**Legal Notice**

Google Play and the Google Play logo are trademarks of Google LLC
