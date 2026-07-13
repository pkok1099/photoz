# Tighten Detekt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn on all Detekt rules with middle-ground thresholds, reactivate previously disabled rules, suppress `@Preview` false positives via path excludes, and regenerate the baseline — keeping CI green throughout.

**Architecture:** Two declarative config files are edited in the editing environment and committed; the baseline XML (which cannot be generated here — no Gradle toolchain) is regenerated on the user's VPS via `./gradlew detektBaseline` and committed as a follow-up. Detekt's `allRules = true` + `buildUponDefaultConfig = true` activates every rule; `excludes:` per path remove the `@Preview` noise; the regenerated baseline absorbs all remaining existing findings so the blocking `./gradlew detekt` CI gate stays green.

**Tech Stack:** Detekt Gradle plugin 1.23.8, Kotlin DSL Gradle (`app/build.gradle.kts`), YAML config (`config/detekt/detekt.yml`), Detekt baseline XML (`config/detekt/detekt-baseline.xml`), GitHub Actions (`android.yml` quality job).

## Global Constraints

- Detekt plugin version is `io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8` — do NOT change it.
- `buildUponDefaultConfig = true` must remain set — the YAML only OVERRIDES defaults, so every rule not listed stays at Detekt default.
- `ignoreFailures = false` must remain set — Detekt is a BLOCKING gate; any finding not in the baseline fails CI.
- The YAML file MUST contain exactly ONE top-level `style:` block (merge the two `style:` snippets from the spec into one).
- The `detekt-baseline.xml` MUST be regenerated via `./gradlew detektBaseline` — NEVER hand-edit it.
- The editing environment has NO Gradle toolchain. Baseline generation is a MANUAL step on the user's VPS, post-push.
- The regenerated baseline's finding count should be LARGER than today's 153. If smaller, `excludes:` over-suppressed — fix before merge.

---

## File Structure

| File | Responsibility | Change type |
|------|----------------|-------------|
| `app/build.gradle.kts` | Detekt Gradle extension config (lines 438-443) | Modify — add `allRules = true` |
| `config/detekt/detekt.yml` | Detekt rule overrides (thresholds, activation, excludes) | Rewrite — middle target |
| `config/detekt/detekt-baseline.xml` | Snapshot of existing findings (safety net) | Regenerate on VPS (no local edit) |
| `.github/workflows/android.yml` | CI `quality` job runs `./gradlew detekt` | No change |

---

## Task 1: Enable `allRules = true` in `app/build.gradle.kts`

**Files:**
- Modify: `app/build.gradle.kts:438-443` (the `detekt { }` block)

**Interfaces:**
- Consumes: nothing external.
- Produces: a `detekt` extension with `allRules = true`; downstream Task 2's `detekt.yml` is what `config.setFrom(...)` points at.

- [ ] **Step 1: Add `allRules = true` to the detekt block**

Open `app/build.gradle.kts`. Replace the existing block (lines 438-443):

```kotlin
detekt {
	config.setFrom(rootProject.files("config/detekt/detekt.yml"))
	buildUponDefaultConfig = true
	parallel = true
	ignoreFailures = false
}
```

with:

```kotlin
detekt {
	config.setFrom(rootProject.files("config/detekt/detekt.yml"))
	baseline = rootProject.file("config/detekt/detekt-baseline.xml")
	buildUponDefaultConfig = true
	allRules = true // activate every detekt rule (Sprint: tighten detekt)
	parallel = true
	ignoreFailures = false // blocking quality gate
}
```

Note: the current `detekt { }` block (lines 438-443) does NOT contain a `baseline =` line (the `baseline` at line 156 belongs to the unrelated `lint` config). Add it explicitly so the path matches the documented config and the `detektBaseline` task output target. This line is required for the baseline to be loaded.

- [ ] **Step 2: Verify the edit is syntactically isolated**

Confirm only `allRules = true` and the explicit `baseline =` line were added; `buildUponDefaultConfig`, `parallel`, and `ignoreFailures = false` are unchanged. No other lines in `app/build.gradle.kts` should change.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: enable detekt allRules=true to activate every rule"
```

---

## Task 2: Rewrite `config/detekt/detekt.yml` to middle thresholds + excludes

**Files:**
- Rewrite: `config/detekt/detekt.yml`

**Interfaces:**
- Consumes: the `allRules = true` flag from Task 1 (so this YAML only needs to express OVERRIDES).
- Produces: a single valid YAML config consumed by `./gradlew detektBaseline` and `./gradlew detekt` on the VPS.

- [ ] **Step 1: Replace the entire contents of `config/detekt/detekt.yml`**

Write EXACTLY this file (note: the `style:` block is a single merged block — the two `style:` snippets from the spec are combined here):

```yaml
# detekt configuration — tightened static analysis
#
# buildUponDefaultConfig=true so this file ONLY overrides detekt defaults.
# allRules=true is set in app/build.gradle.kts (activates every rule).
# Philosophy: middle-ground strictness — detekt defaults with pragmatic
# tolerance for Compose UI functions and Hilt DI constructors. The baseline
# file (detekt-baseline.xml) absorbs remaining existing findings so CI stays
# green; new violations still fail the build (ignoreFailures=false).

style:
  # Wildcard imports are a real smell; ktlint already enforces style.
  WildcardImport:
    active: true
  # 120 is detekt default; package/import lines excluded.
  MaxLineLength:
    maxLineLength: 120
    excludePackageStatements: true
    excludeImportStatements: true
  ReturnCount:
    max: 3 # toward default 2, pragmatic middle
  # Magic-number crypto constants (16/32/256) are legitimate → keep off.
  MagicNumber:
    active: false
  # Loop vars aren't properties; rule default allowedNames handles them.
  UnusedPrivateProperty:
    active: true
  # @Preview Composables are intentionally unused private members.
  UnusedPrivateMember:
    excludes: ['**/*Preview*.kt', '**/preview/**']
  UnusedPrivateFunction:
    excludes: ['**/*Preview*.kt', '**/preview/**']

complexity:
  LongMethod:
    threshold: 80 # toward default 60 (Compose-tolerant)
  LongParameterList:
    functionThreshold: 8 # default 6, DI/Compose need room
    constructorThreshold: 10 # Hilt @Provides constructors
  CyclomaticComplexMethod:
    threshold: 15 # detekt default
  NestedBlockDepth:
    threshold: 5 # Compose nesting
  TooManyFunctions:
    thresholdInObjects: 15
    thresholdInClasses: 15

exceptions:
  # Compose/network code catches broadly by design → stays off.
  SwallowedException:
    active: false
  TooGenericExceptionCaught:
    active: false
  TooGenericExceptionThrown:
    active: false

empty-blocks:
  EmptyFunctionBlock:
    active: false # intentional interface stubs

naming:
  FunctionNaming:
    functionPattern: '([a-z][a-zA-Z0-9]*)|([A-Z][a-zA-Z0-9]*)'
  VariableNaming:
    variablePattern: '(_)?[a-zA-Z][a-zA-Z0-9]*|_'

potential-bugs:
  UnsafeCallOnNullableType:
    active: true # real NPE risk, now enforced
  CastToNullableType:
    active: true

performance:
  ForEachOnRange:
    active: false # `for (i in 0..n)` is idiomatic
  SpreadOperator:
    active: false # few varargs sites

comments:
  # TODO allowed (part of workflow); keep FIXME/STOPSHIP strict.
  ForbiddenComment:
    comments: ['FIXME:', 'STOPSHIP:']
```

- [ ] **Step 2: Sanity-check YAML validity (no Gradle needed)**

Run a quick structural check. If Python 3 is available:

```bash
python3 -c "import yaml,sys; d=yaml.safe_load(open('config/detekt/detekt.yml')); print('top-level keys:', list(d.keys())); assert list(d.keys()).count('style')==1, 'duplicate style block'; print('OK')"
```

Expected: prints the top-level keys and `OK`, with exactly one `style` key. If `pyyaml` is missing, install via `pip install pyyaml` or visually confirm there is only ONE `style:` at column 0.

- [ ] **Step 3: Commit**

```bash
git add config/detekt/detekt.yml
git commit -m "build: tighten detekt config to middle thresholds + @Preview excludes"
```

---

## Task 3: Push config + build changes (editing env → remote)

**Files:**
- Push: commits from Task 1 and Task 2 (do NOT push a baseline yet).

**Interfaces:**
- Consumes: the two committed config commits.
- Produces: a remote branch state the user can pull on the VPS to run Gradle.

- [ ] **Step 1: Verify local commits exist and are clean**

```bash
git status
git log --oneline -3
```

Expected: `git status` clean; the last two commits are the `app/build.gradle.kts` and `detekt.yml` changes. No `detekt-baseline.xml` change present.

- [ ] **Step 2: Push to remote**

```bash
git push
```

Expected: push succeeds. Note the branch name for the VPS step.

---

## Task 4: Regenerate baseline on VPS (MANUAL — requires Gradle)

**Note:** This task CANNOT run in the editing environment (no Gradle). It is performed by the user on a machine with the Android SDK + Gradle. It is listed here as the explicit manual handoff step from the spec Section 6.

**Files:**
- Regenerate: `config/detekt/detekt-baseline.xml` (output of `detektBaseline` task)

**Interfaces:**
- Consumes: the pushed `app/build.gradle.kts` + `detekt.yml`.
- Produces: a regenerated `detekt-baseline.xml` that the CI `quality` job will consume.

- [ ] **Step 1: Pull the pushed branch on the VPS**

```bash
git pull
```

- [ ] **Step 2: Generate the full baseline**

```bash
./gradlew detektBaseline --stacktrace
```

Expected: task succeeds and overwrites `config/detekt/detekt-baseline.xml` with every current finding under `allRules=true` + middle thresholds.

- [ ] **Step 3: Verify finding count grew (guardrail)**

```bash
grep -c '<ID>' config/detekt/detekt-baseline.xml
```

Expected: count is LARGER than 153 (today's baseline). If it is smaller, the `excludes:` rules over-suppressed — revisit `config/detekt/detekt.yml` `excludes:` before proceeding.

- [ ] **Step 4: Confirm `detekt` passes against the new baseline**

```bash
./gradlew detekt --stacktrace
```

Expected: BUILD SUCCESSFUL (exit 0). The baseline absorbs all current findings, so the `quality` CI job will be green.

- [ ] **Step 5: Commit and push the regenerated baseline**

```bash
git add config/detekt/detekt-baseline.xml
git commit -m "build: regenerate detekt baseline for allRules + tightened config"
git push
```

---

## Task 5: Confirm CI quality job is green

**Files:**
- Verify: `.github/workflows/android.yml` `quality` job (no change needed)

**Interfaces:**
- Consumes: the pushed regenerated baseline + config.
- Produces: confirmation that the blocking gate passes.

- [ ] **Step 1: Open the Actions run for the pushed baseline commit**

Navigate to the `quality` job in the GitHub Actions run triggered by Task 4 Step 5's push.

- [ ] **Step 2: Confirm the `detekt` step passed**

Expected: the "Run detekt" step is green and the "Upload detekt report" artifact is produced. If red, the baseline was not regenerated against the same config — re-run Task 4 on the VPS and push again.

---

## Follow-up (NOT in this plan — later PRs, per spec Section 5)

These are out of scope for the initial rollout but documented so the ratchet continues:

- Re-run `./gradlew detektBaseline` on VPS after adding more `excludes:` or fixing real smells, to shrink the baseline toward only genuine deferred refactors.
- If type-resolution rules (`UnusedImport`, `SafeCast`, `CanBeNonNullable`) do not appear in the baseline/report, bind `detektMain`/`detektAnalysis` tasks explicitly.
- Never hand-edit `detekt-baseline.xml`; always regenerate.

---

## Self-Review Notes (plan author)

- **Spec coverage:** Task 1 = spec Section 3 (`allRules=true`). Task 2 = spec Section 4 (yml rewrite, merged single `style:` block). Task 4 = spec Section 5/6 (baseline regen on VPS, count > 153 check). Task 5 = spec Section 7 (CI green). All 11 previously-disabled rules accounted for: 4 reactivated (`WildcardImport`, `UnusedPrivateProperty`, `UnsafeCallOnNullableType`, `CastToNullableType`), 7 kept off (`MagicNumber`, `SwallowedException`, `TooGenericExceptionCaught`, `TooGenericExceptionThrown`, `EmptyFunctionBlock`, `ForEachOnRange`, `SpreadOperator`).
- **Placeholder scan:** No TBD/TODO/“implement later”. Every code step shows the exact file content or command.
- **Type/name consistency:** `allRules`, `buildUponDefaultConfig`, `ignoreFailures`, `baseline` consistent across Task 1 and Task 4. Single `style:` block enforced in Task 2 Step 1 + Step 2 assertion.
- **No unit tests:** Detekt config is declarative; verification is the build task (spec Section 7 confirms none).
