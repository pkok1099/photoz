# Design: Perketat Detekt (Tighten Detekt Static Analysis)

- **Date:** 2026-07-13
- **Status:** Approved (design), pending implementation plan
- **Owner:** photoz maintainer
- **Related:** Sprint 4 code-quality tooling (detekt + ktlint), Sprint 5 ktlint blocking

## 1. Context

The project uses Detekt 1.23.8 as a **blocking** static-analysis gate in CI
(`.github/workflows/android.yml` → `quality` job runs `./gradlew detekt`).

Today the config (`config/detekt/detekt.yml`) is deliberately permissive
("start permissive, tighten over time"):

- **11 rules fully disabled** (`active: false`): `WildcardImport`,
  `MagicNumber`, `UnusedPrivateProperty`, `UnsafeCallOnNullableType`,
  `CastToNullableType`, `SwallowedException`, `TooGenericExceptionCaught`,
  `TooGenericExceptionThrown`, `EmptyFunctionBlock`, `ForEachOnRange`,
  `SpreadOperator`.
- **Thresholds raised well above Detekt defaults:** `MaxLineLength: 200`,
  `LongMethod: 120`, `LongParameterList` 8/10, `CyclomaticComplexMethod: 20`,
  `NestedBlockDepth: 5`, `ReturnCount: 4`.
- `buildUponDefaultConfig = true` is set, so the YAML only *overrides* defaults.
- `config/detekt/detekt-baseline.xml` carries **153 existing issues** so CI
  does not break on day 1. Most are `@Preview` Composable false positives
  (`UnusedPrivateMember`), `LongParameterList`/`LongMethod` on DI constructors
  and Compose UIs, and `LoopWithTooManyJumpStatements`.

**Goal of this work:** honor all four tightening levers the user selected —
reactivate disabled rules, tighten thresholds, shrink the baseline, and turn
on `allRules` — while keeping CI green throughout, using a **full-baseline-first**
rollout, a **middle** threshold target (Detekt defaults with Compose/DI
tolerance), **type-resolution** enabled, and **per-path `excludes:`** for noisy
rules instead of blanket-disabling.

## 2. Goals & Non-Goals

### Goals
- Turn on all Detekt rules (`allRules = true`) on top of defaults
  (`buildUponDefaultConfig = true`), then reactivate the rules currently
  disabled in `detekt.yml`.
- Tighten thresholds to the **middle** target (Detekt defaults with Compose/DI
  tolerance), documented as a deviation from today's loose values.
- Fix the `@Preview` Composable false-positive flood via per-path `excludes:`
  instead of blanket-disabling `UnusedPrivateMember`.
- Enable type-resolution rules (detekt runs after compilation) so `UnusedImport`,
  `SafeCast`, `CanBeNonNullable`, etc. actually fire.
- Keep CI green throughout: regenerate a full baseline first, then shrink it
  incrementally over follow-up PRs.

### Non-Goals (YAGNI)
- Not rewriting existing long/complex functions just to satisfy thresholds —
  those stay in the baseline until someone touches the file.
- Not applying the same strictness to test source sets via a separate config
  (covered by `excludes:`/source-set scoping).
- Not changing ktlint (already blocking & stable).
- Not removing the baseline mechanism itself — it stays as the safety net.

## 3. `app/build.gradle.kts` changes

Only the `detekt { }` block in `app/build.gradle.kts` (lines ~435-441) changes.
New state:

```kotlin
detekt {
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/detekt-baseline.xml")
    buildUponDefaultConfig = true
    allRules = true                 // NEW — activate every rule
    parallel = true
    ignoreFailures = false          // stays a blocking gate
    // failOnSeverity stays at default (Error)
}
```

### Type-resolution
With the `io.gitlab.arturbosch.detekt` plugin, type-resolution rules run
automatically when `detekt` executes in the task graph after
`compileKotlin`/`compileJava` — the plugin wires the classpath and source set.
Running `./gradlew detekt` already depends on compilation, so no extra task
wiring is needed. If the implementation pass finds a rule reporting "requires
type resolution" without firing, the fix is to bind `detektMain`/`detektAnalysis`
explicitly; handle during execution, not design.

### Risk
`allRules = true` with the new middle thresholds surfaces a large number of
*new* findings. Section 6 (baseline regeneration) absorbs them so CI stays green.

## 4. `config/detekt/detekt.yml` rewrite (middle target + excludes)

The config keeps `buildUponDefaultConfig = true`, so it only needs to express
**overrides**: reactivated rules, tightened thresholds, and `excludes:` for
noise.

```yaml
# buildUponDefaultConfig=true → everything below OVERRIDES detekt defaults.
# allRules=true is set in app/build.gradle.kts.

style:
  # RE-ACTIVATE (was active:false). Wildcards are a real smell; ktlint
  # already enforces style, so this is now safe.
  WildcardImport:
    active: true
  # 120 is detekt default; import/package lines excluded via defaults.
  MaxLineLength:
    maxLineLength: 120
    excludePackageStatements: true
    excludeImportStatements: true
  ReturnCount:
    max: 3            # was 4 → toward default 2, pragmatic middle
  # Magic-number crypto constants are legitimate → keep OFF, documented.
  MagicNumber:
    active: false
  # Loop vars aren't properties; the rule's default allowedNames handles it.
  UnusedPrivateProperty:
    active: true

# NOTE: the `style:` keys above and below are shown separately for readability
# but MUST be merged into a single `style:` block in the actual detekt.yml file
# (YAML does not allow duplicate top-level keys).
complexity:
  LongMethod:
    threshold: 80     # was 120 → toward default 60 (Compose-tolerant)
  LongParameterList:
    functionThreshold: 8     # default 6, but DI/Compose need room
    constructorThreshold: 10 # Hilt @Provides constructors
  CyclomaticComplexMethod:
    threshold: 15    # was 20 → detekt default
  NestedBlockDepth:
    threshold: 5     # keep (Compose nesting)
  TooManyFunctions:
    thresholdInObjects: 15
    thresholdInClasses: 15

exceptions:
  # Compose/network code catches broadly by design → stays OFF, documented.
  SwallowedException:
    active: false
  TooGenericExceptionCaught:
    active: false
  TooGenericExceptionThrown:
    active: false

empty-blocks:
  EmptyFunctionBlock:
    active: false    # intentional interface stubs

naming:
  FunctionNaming:
    functionPattern: '([a-z][a-zA-Z0-9]*)|([A-Z][a-zA-Z0-9]*)'
  VariableNaming:
    variablePattern: '(_)?[a-zA-Z][a-zA-Z0-9]*|_'

potential-bugs:
  UnsafeCallOnNullableType:
    active: true     # was false — real NPE risk, now enforced
  CastToNullableType:
    active: true     # was false

performance:
  ForEachOnRange:
    active: false    # `for (i in 0..n)` is idiomatic here
  SpreadOperator:
    active: false    # few varargs sites, not worth blocking

# Per-path excludes so noisy rules don't need to be disabled.
# @Preview Composables are intentionally unused private members.
style:
  UnusedPrivateMember:
    excludes: ['**/*Preview*.kt', '**/preview/**']
  UnusedPrivateFunction:
    excludes: ['**/*Preview*.kt', '**/preview/**']
comments:
  ForbiddenComment:
    # TODO allowed (baseline already has them); keep FIXME/STOPSHIP strict.
    comments: ['FIXME:', 'STOPSHIP:']
```

### Notes
- `UnusedPrivateMember`/`UnusedPrivateFunction` `excludes:` replace the old
  blanket-off-by-baseline approach for `@Preview` — this **automatically**
  evaporates most of the 153 baseline `@Preview` entries, so shrinking the
  baseline later needs no edits there.
- `MagicNumber` stays off (crypto constants `16/32/256`),
  `SwallowedException`/`TooGenericException*` stay off (network/Compose by
  design), `EmptyFunctionBlock` off (intentional stubs),
  `ForEachOnRange`/`SpreadOperator` off (idiomatic/rare) — these are deliberate,
  documented deviations. Everything else that was `active:false` is
  **reactivated**.
- `ForbiddenComment` narrowed to `FIXME:`/`STOPSHIP:` instead of the full TODO
  list — TODOs are part of the team's workflow.

## 5. Baseline regeneration & incremental shrink

This is what keeps CI green through the rollout.

### Step 1 — Full baseline (this PR)
After the `detekt.yml` + `build.gradle.kts` change, generate the baseline with
`./gradlew detektBaseline` (requires a Gradle toolchain — see Section 6). This
regenerates `config/detekt/detekt-baseline.xml` to capture **every** current
finding under `allRules=true` + middle thresholds. The `detekt` CI task then
passes (baseline absorbs everything), so the gate stays green. Commit the
regenerated baseline alongside the config.

### Step 2 — Shrink over follow-up PRs (specified, not this PR)
Now that `excludes:` handles `@Preview`, a subsequent `./gradlew detektBaseline`
drops those `@Preview` entries automatically. Then shrink incrementally:
- For each baseline issue: either **fix it at source** (real smell) or confirm
  it's covered by a new `excludes:` rule, then regenerate the baseline to remove
  it.
- Never hand-edit the baseline XML — always regenerate via `detektBaseline` so
  IDs stay consistent.
- Target end-state: baseline contains only genuine, deferred refactors (long
  Compose funcs, large DI classes) — not false positives.

### Guardrail
`ignoreFailures = false` means any **new** violation (not in baseline) still
fails CI immediately. So tightening never silently regresses — the baseline is a
one-way ratchet toward fewer exceptions.

## 6. Rollout sequence (concrete steps)

The Gradle toolchain is **not available in the editing environment** (no
`./gradlew` here). All baseline generation happens on a machine/VPS that has
Gradle (the user's VPS, post-push). This is a manual step, called out explicitly.

1. **Editing environment (Claude):** Modify `app/build.gradle.kts`
   (`allRules = true`) and rewrite `config/detekt/detekt.yml` (middle thresholds
   + `excludes:`, reactivated rules). Commit these two files. **Do NOT commit a
   baseline yet** — it cannot be generated here.
2. **Push** the config + build changes.
3. **VPS (user, with Gradle):** `./gradlew detektBaseline` → regenerates
   `config/detekt/detekt-baseline.xml` under the new strict config. Verify the
   report finding count is **larger** than today's 153 (more rules on). If
   unexpectedly smaller, `excludes:` over-suppressed — fix before merge.
4. **VPS:** `./gradlew detekt` → must exit 0 (baseline absorbs everything).
   Confirms the `quality` CI job will be green.
5. **Commit & push** the regenerated baseline as a follow-up commit.
6. **CI:** `quality` job runs `./gradlew detekt` → green.

### Shrink phase (later PRs, per Section 5)
Add `excludes:`, fix real smells, re-run `detektBaseline` on VPS, commit narrower
baseline.

## 7. Verification & testing

### Local (editing environment)
None possible — no Gradle toolchain here. All verification happens in CI / on
the VPS.

### CI / VPS verification
1. The PR's push triggers the `quality` job in `.github/workflows/android.yml`,
   which runs `./gradlew detekt`.
2. Because the **regenerated** `detekt-baseline.xml` is committed in the same
   flow, the `detekt` task must pass (baseline absorbs all current findings).
   CI green = verified.
3. **Sequencing for the PR:** the baseline must be generated in an environment
   that has Gradle (VPS) via `./gradlew detektBaseline`, then committed. This is
   a manual step (cannot run in the editing environment).
4. Guardrail check via CI/VPS logs: after the `detekt` run, the task summary /
   `detekt-report` artifact shows the finding count. It should be **larger** than
   today's 153 (more rules on). If unexpectedly smaller, `excludes:` over-
   suppressed.

### Type-resolution proof
Confirmed indirectly — if `UnusedImport`/`SafeCast` findings appear in the
baseline/report, type-resolution is wired. If they are absent everywhere they
should fire, that is a follow-up to bind `detektMain`/`detektAnalysis` explicitly.

### Regression guard
`ignoreFailures = false` + committed baseline = any new violation fails CI.
One-way ratchet.

### Unit tests
None. Detekt config is declarative; verification is the build task itself, not
JUnit.

## 8. Files touched

| File | Change |
|------|--------|
| `app/build.gradle.kts` | Add `allRules = true` to `detekt { }` block (~line 435) |
| `config/detekt/detekt.yml` | Rewrite: middle thresholds, reactivated rules, `excludes:` for `@Preview`, narrow `ForbiddenComment` |
| `config/detekt/detekt-baseline.xml` | **Regenerated** on VPS via `detektBaseline` (not edited by hand) |
| `.github/workflows/android.yml` | No change (already runs `./gradlew detekt` in `quality` job) |

## 9. Open questions / follow-ups

- If type-resolution rules do not fire after `allRules=true`, bind
  `detektMain`/`detektAnalysis` tasks explicitly (investigate during execution).
- Periodic baseline-shrink PRs to drive the baseline toward only genuine deferred
  refactors.
