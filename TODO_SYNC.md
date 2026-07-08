# PhotoZ TODO — Rclone Gomobile JNI Migration (verify + audit)

> Created: 2026-07-08
> Context: App migrated from rclone **subprocess** (`ProcessBuilder` + `librclone.so` EXEC)
> to **gomobile JNI** (`libgojni.so` via `dlopen`, RC API). A real bug was hit during this
> migration (`options/set {"main":{"Config":...}}` is silently ignored by rclone → config
> path never applied → "didn't find section in config file"). Fixed via `config/setpath`
> in commit `8e2bd21e`. This TODO tracks verification of the new impl and audit of leftover
> old logic.

---

## A. Verify new gomobile JNI implementation

- [ ] **A1. `config/setpath` works on device** — build B2 (`8e2bd21e`), import sample
      `rclone.conf`, run a sync op. Logcat `RcloneController` must show
      `Applied rclone config path via config/setpath: /data/data/onlasdan.gallery/files/rclone/rclone.conf`.
      No "didn't find section" on `operations/*`.
- [ ] **A2. Library load** — `System.loadLibrary("gojni")` resolves `libgojni.so` from the
      gomobile AAR (committed under `app/libs` or pulled via maven). Confirm the AAR is
      actually packaged in the APK (`apkanalyzer` / `unzip -l`).
- [ ] **A3. Single-ABI match** — CI/build restricts to `arm64-v8a` ("arm64-only"). Confirm the
      gomobile AAR only contains `arm64-v8a` (or that armeabi-v7a is intentionally excluded
      and no device needs it).
- [ ] **A4. All RC calls work via gomobile** — `core/version`, `operations/copyfile`,
      `operations/list`, `operations/deletefile`, `operations/stat`, `operations/about`.
      None may rely on CLI exit codes / `--flags` (subprocess model). Assert each returns
      valid JSON, not an error string.
- [ ] **A5. ProGuard / R8** — `gomobile.**` and `onlasdan.gallery.sync.rclone.**` are kept
      (`app/proguard-rules.pro`). Verify `Gomobile.rcloneInitialize/rcloneRPC/rcloneFinalize`
      and the reflected `getOutput()` survive R8 full mode (recent commit fixed ProGuard rules —
      confirm with a release build + smoke test).
- [ ] **A6. 16KB page alignment** — gomobile `.so` built with 16KB alignment
      (`build-rclone-gomobile.yml`) so `dlopen` works on Android 16. Verify `.so` alignment
      (`readelf -lW libgojni.so` → LOAD align 0x4000).

## B. Audit old (subprocess) logic — remove leftovers

- [ ] **B1. Stale build.gradle comment** — `app/build.gradle.kts:135-149` still describes
      `ProcessBuilder` + `librclone.so` EXEC + `useLegacyPackaging = true`. For a pure gomobile
      JNI lib loaded via `dlopen`/`System.loadLibrary`, the modern default is
      `useLegacyPackaging = false` (uncompressed, page-aligned, mmap'd). Review whether
      `useLegacyPackaging = true` is still needed; if not, flip to `false` and rewrite the
      comment to describe gomobile JNI. Keep 16KB-alignment guarantee.
- [ ] **B2. Stale `locateRcloneBinary()` comments** — `RepoManager.kt:252,286` reference a
      function that no longer exists. Replace with gomobile-equivalent wording (config set via
      `config/setpath`, no binary lookup).
- [ ] **B3. No subprocess residue** — grep confirms no `ProcessBuilder`/`Runtime.exec` in code
      (only comments). Remove the remaining comment mentions so the old model is fully gone.
- [ ] **B4. Config reload semantics** — rclone caches config for the process lifetime;
      `config/setpath` only affects reads **before the first config access**. Re-importing
      `rclone.conf` to the same path will NOT reload it until process restart. Document this
      (or decide if `stop()`+`ensureInitialized()` re-apply is sufficient). Confirm re-import UX.
- [ ] **B5. `RcloneDiag` logs** — extensive `android.util.Log.i/w` diagnostic calls
      (`RcloneDiag` tag) were added while debugging. Gate behind `BuildConfig.DEBUG` or remove
      before release so they don't leak remote names / paths in production logcat.
- [ ] **B6. Sample `rclone.conf`** — repo-root `rclone.conf` is a fixture with fake creds.
      Ensure it is gitignored / not shipped in the APK (it is not under `app/src`, so safe, but
      verify it isn't referenced by any test asset path).

## Out of scope (tracked elsewhere)
- SQLCipher (ROADMAP2 #6), Argon2id (ROADMAP2 #3), chunked encryption (TODO1 #2),
  multi-vault registry merge (TODO1 #16) — unchanged by this migration.
