![PhotoZ](fastlane/metadata/android/en-US/images/featureGraphic.jpg)

# PhotoZ

Private photo vault for Android with strong on-device encryption and optional
end-to-end encrypted cloud sync via rclone.

## About

PhotoZ is a secure private photo vault for Android that helps you hide photos
and videos using strong AES-256 encryption. It protects sensitive media in an
encrypted gallery and keeps your private memories safe on your own device.

All files are encrypted locally with AES-256 (CBC for media bodies, GCM for
metadata) and only decrypted in memory while you use the app. The cloud-sync
feature (optional) mirrors the encrypted artifacts to **your own** rclone remote
— PhotoZ never sees your photos, your password, or your recovery phrase.

PhotoZ is open source, ad-free, and built with a privacy-first philosophy.

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width="24%" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" width="24%" />
</p>

## Download

PhotoZ is distributed as APKs from GitHub releases and sideloaded directly.

[<img src="https://raw.githubusercontent.com/andOTP/andOTP/master/assets/badges/get-it-on-github.png"
      alt="Get it on GitHub"
      height="80">](https://github.com/leonlatsch/Photok/releases/latest)

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

### Cloud sync (optional, rclone-backed)

PhotoZ can mirror your encrypted photos to **your own** cloud storage via
[rclone](https://rclone.org). The sync feature is end-to-end encrypted: only
the encrypted artifacts ever leave your device.

- **Encrypted artifacts on the remote** — originals (`<uuid>.crypt`), thumbnails
  (`<uuid>.crypt.tn`), and video previews (`<uuid>.crypt.vp`) are AES-CBC
  encrypted with the Vault Master Key (VMK) before upload. An attacker with
  read access to your remote sees only opaque ciphertext.
- **Content-hash dedup** — every photo's plaintext bytes are SHA-256'd at import
  time. Before uploading, the worker consults an encrypted dedup registry
  (`registry.json.crypt`) on the remote; if the same content-hash is already
  present under a different UUID, the upload is skipped (saving bandwidth and
  remote storage). The duplicate photo is still marked `UPLOADED` locally so
  the gallery's sync badge shows the correct state immediately.
- **Encrypted dedup registry** — one encrypted JSON artifact
  (`registry.json.crypt`) on the remote, AES-256-GCM encrypted with the VMK.
  Contains one entry per content-hash: canonical UUID, filename, album path,
  size, type, and (optionally) packed-thumbnail location.
- **Packed thumbnails** — at batch end, thumbnails are concatenated into
  ≤50 MB packs (`thumbnails/pack-*.pack`) and uploaded as a single file per
  pack, instead of N individual round-trips for N thumbnails. The per-hash
  `thumbnail_pack` / `thumbnail_offset` / `thumbnail_length` fields in the
  registry record each thumbnail's location within its pack. On restore, each
  pack is downloaded once and thumbnails are extracted by offset+length.
- **Two-layer key escrow** — fresh-install recovery is supported via two
  encrypted artifacts on the remote:
  - `vault-protection/recovery-phrase.json.crypt` (Layer 1): the VMK wrapped
    with a phrase-derived KEK. The entire JSON is additionally AES-256-GCM
    encrypted with the VMK so the structure (field names, base64 strings) is
    hidden from anyone with read access to the remote.
  - `vault-protection/wrapped-phrase.json.crypt` (Layer 2): the recovery
    phrase wrapped with a password-derived KEK. Same outer GCM encryption
    with the VMK.
  - On a fresh install, the user enters their password (Layer 2 is unwrapped
    to recover the phrase, which then unwraps the VMK from Layer 1) OR their
    recovery phrase directly (Layer 1 is unwrapped to recover the VMK).
  - **Backwards compat**: legacy plaintext `recovery-phrase.json` and
    `wrapped-phrase.json` files from older repos are still readable by the
    download path as a fallback.
- **Restore from cloud** — the gallery's overflow menu has a "Restore from
  backup" option that re-downloads any missing thumbnails from the cloud
  (via packs, with legacy individual-thumbnail fallback). Useful when a prior
  restore was interrupted or when local thumbnails were deleted.
- **Drive-style notifications** — sync runs as a foreground WorkManager job
  with a persistent notification showing batch-level progress
  ("Uploading N of M: filename (size)"), a final batch-complete summary
  ("{N} photos backed up"), and per-photo failure notifications.

### Privacy-focused design

PhotoZ is designed for people who want real control over their private photos
and videos. Encryption happens on your device, and imported files are only
decrypted in memory while the app is in use. The cloud sync uses your own
rclone remote — PhotoZ has no server component and never sees your photos,
your password, or your recovery phrase.

PhotoZ can use minimal privacy-friendly analytics to improve stability and
user experience. These analytics are never used for advertising or cross-app
tracking.

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
| **Telemetry** | Enabled by default (can be disabled in settings) | Disabled by default (can be enabled in settings) |
| **Telemetry opt-in prompt** | No prompt shown | Shown once after first unlock |
| **In-App Review** | Requested after usage milestones | Not available |

> **Why is telemetry off by default on FOSS?** F-Droid flags apps that transmit data without explicit opt-in as having the *Tracking* anti-feature. No data is ever transmitted unless the user has actively opted in.

## Build

PhotoZ is a standard Android Gradle project. Build variants:

- `play` — Google Play release (includes TelemetryDeck).
- `foss` — F-Droid / sideload release (no telemetry).

```bash
# Debug build (FOSS flavor)
./gradlew :app:assembleFossDebug

# Release build (Play flavor)
./gradlew :app:assemblePlayRelease
```

The cloud-sync feature uses an embedded rclone **shared library** (loaded via
gomobile JNI / `dlopen` from `app/libs/librclone.aar`). No subprocess is spawned;
this is W^X-safe on Android 16.

### Database

PhotoZ uses a Room database (`photok.db`). The current schema version is 16.
Schema changes use Room auto-migrations declared in `PhotokDatabase.kt` (file
name) / `PhotoZDatabase` class. Since v16, the main DB is encrypted at-rest
with SQLCipher (key backed by Android Keystore); a separate plaintext
`BootstrapDatabase` (`photok_meta.db`) holds the `vault_protection` table so
it can be read before the encrypted DB is unlocked.

## Community

### Contributors

<a href="https://github.com/leonlatsch/Photok/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=leonlatsch/Photok"  alt="PhotoZ Contributors"/>
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
