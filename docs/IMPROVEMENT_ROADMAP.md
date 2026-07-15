# Git Worktree Manager — Improvement Roadmap

> Status: Draft plan (assessment-based)  
> Scope: polish, trust, maintainability, Marketplace readiness  
> Constraint: no calendar estimates — scope is described by technical surface and risk

---

## Verdict

Core product is solid: worktree CRUD, merge/pull/push, ignored-file copy, and error dialogs are above average for a small plugin.

Main gaps are **trust/security (hardcoded telemetry credentials)**, **UI concentration (~1.4k-line Compose surface)**, **near-empty i18n**, **half-wired features**, and **CI without Plugin Verifier**.

---

## Current strengths (keep)

| Area | Evidence |
|------|----------|
| Layered packages | `repository` / `viewmodel` / `services` / `ui/dialogs` / `models` |
| Error UX | `UiErrorMapper` + `ErrorDetailsDialog` |
| Release automation | Tag publish, signing secrets, change-notes script |
| CI packaging | `buildPlugin` + artifact upload on master |
| Manual QA doc | `docs/TESTING_CHECKLIST.md` |

---

## Priority matrix

| P | Theme | Why it matters | Risk if deferred |
|---|--------|----------------|-------------------|
| P0 | Telemetry credentials & privacy | Marketplace trust; key extractable from zip | Key abuse / policy rejection |
| P1 | Functional correctness | Copy race; optional Compose = no UI | Silent failures / empty install |
| P2 | Product surface cleanup | Agent Context / Remote dialogs unused | Dead code, confused users |
| P3 | UI split + visual polish | `MyToolWindow.kt` ~1416 LOC | Hard to change safely |
| P4 | i18n + Marketplace listing | Bundle unused; description incomplete | Weak Marketplace presence |
| P5 | Quality gates | No Plugin Verifier / lint | Breaks on new IDEA builds |
| P6 | Docs hygiene | README scratch notes; outdated checklist | Contributor friction |

---

## Phase 0 — Trust & telemetry (P0)

**Goal:** No secrets in the shipped plugin; clear privacy posture.

### Work items

1. **Rotate/revoke** the New Relic insert key currently Base64-embedded in `TelemetryService.kt` (`ApiKeyHolder`).
2. Remove hardcoded key/account from source.
3. Choose one delivery model:
   - **Recommended:** telemetry **off by default**; optional Settings toggle; key supplied at build time via CI secret / `local.properties` (never committed), **or**
   - Disable outbound telemetry entirely until a proper consent UX exists.
4. Stop sending high-risk fields by default (`worktree_path`, full `error_message`, stack traces) or scrub aggressively.
5. Add a short **Privacy** note to README / Marketplace description.
6. Align plugin telemetry with GitHub workflows that already use Actions secrets (`newrelic-*.yml`).

### Exit criteria

- [ ] `rg` finds no NR key/account literals under `src/`
- [ ] Fresh install sends no telemetry unless user opts in (or telemetry fully disabled)
- [ ] Key rotated in New Relic dashboard

### Touch points

- `services/TelemetryService.kt`
- `plugin.xml` (optional Settings configurable)
- `README.md`, Marketplace description

---

## Phase 1 — Correctness & packaging (P1)

**Goal:** Install always shows UI; create+copy is reliable.

### 1A. Compose dependency

- Today: `com.intellij.modules.compose` is **optional**; tool window lives only in `compose.xml`.
- Change to a **hard** dependency (or provide a Swing fallback tool window — higher cost).
- Prefer hard dependency unless supporting Compose-less IDEs is a stated goal.

### 1B. Create-worktree + copy race

```text
create success → fire-and-forget copyIgnoredFiles → refresh + onSuccess
```

Fix: await copy (with progress) before success UI / `CopyResultDialog`; surface partial failures consistently.

### 1C. Busy-state consistency

- Create / Refresh / Prune / Merge / Pull share one busy model so actions can’t overlap incorrectly.

### Exit criteria

- [ ] Plugin without Compose module either refuses install or shows a clear fallback
- [ ] Unit/integration test covers “create + copy finishes before success callback”
- [ ] Concurrent action buttons respect shared busy flags

### Touch points

- `plugin.xml`, `compose.xml`
- `viewmodel/WorktreeViewModel.kt`
- `MyToolWindow.kt` create flow

---

## Phase 2 — Product surface: ship or hide (P2)

**Goal:** No half-wired features in `master`.

| Feature | Status | Decision options |
|---------|--------|------------------|
| Claude Agent Context copy | Service + dialogs + ViewModel API; **not wired** into Create UI | **A)** Wire into create flow behind checkbox **B)** Move to `feature/` branch / remove from main |
| `RemoteBranchSelectionDialog` | Unused; remote resolve exists in `GitWorktreeService` | Wire into create dialog **or** delete |
| Tool window SVG icons | Unused (`pluginIcon` used instead) | Wire proper tool-window icons **or** delete orphans |
| Duplicate `sanitizeBranchName` | Dialog + tool window | Single shared util |

### Exit criteria

- [ ] Every public dialog/service is reachable from UI **or** removed
- [ ] No duplicate branch-sanitization helpers

---

## Phase 3 — UI architecture & visual polish (P3)

**Goal:** One job per file; IDE-native look; accessible actions.

### 3A. Split `MyToolWindow.kt`

Suggested modules (names illustrative):

| Module | Responsibility |
|--------|----------------|
| `MyToolWindowFactory` | Factory + wiring only |
| `ui/WorktreeListScreen` | List + empty / loading states |
| `ui/WorktreeToolbar` | Create / Refresh / Prune / Clear |
| `ui/WorktreeRow` | Row actions + context menu |
| `ui/WorktreeOpenHelpers` | Open / focus / modality-safe invokeLater |
| Keep tested pure helpers | Path/sort/delete logic stays unit-tested |

### 3B. Visual & interaction polish

1. Replace deprecated `pointerMoveFilter` and custom floating tooltips with platform/Jewel tooltips.
2. Prefer LaF / Jewel tokens over hardcoded `Color(0x…)`.
3. Add accessible names / semantics on icon-only actions.
4. Make **Open** discoverable (toolbar button or single-click affordance, not only double-click).
5. Progress: prefer Compose/Jewel progress over mixed `SwingPanel` + `JProgressBar` where feasible.
6. Tighten toolbar density for narrow tool windows (overflow menu if needed).

### Exit criteria

- [ ] No Kotlin file in UI layer > ~400 LOC without a documented exception
- [ ] Build warning-free for deprecated pointer/Terminal APIs used by this plugin
- [ ] Keyboard + screen-reader basics for primary actions

---

## Phase 4 — i18n & Marketplace listing (P4)

**Goal:** Strings in bundles; listing matches product.

### Work items

1. Move user-visible strings from `MyToolWindow`, dialogs, `UiErrorMapper`, `NoRepositoryUiHelper` into `messages/MyMessageBundle.properties`.
2. Fix stale stripe key (`toolwindow.stripe.MyToolWindow` vs tool window id `GitWorktrees`).
3. Update Marketplace / `plugin.xml` description to include merge, prune, search, and non-Android use cases.
4. Align vendor URL with this repository if ownership branding is intentional.
5. Optional: `zh_CN` (and others) once English bundle is complete.

### Exit criteria

- [ ] Production UI calls `MyMessageBundle.message(...)` for user-facing text
- [ ] Description lists all shipped capabilities
- [ ] Tool window stripe label resolves correctly

---

## Phase 5 — Quality gates & dependency hygiene (P5)

**Goal:** Catch IDE/API breakage before users do.

### CI / Gradle

1. Bump `org.jetbrains.intellij.platform` **2.10.2 → current stable (e.g. 2.18.x)** and fix any migration deltas.
2. Add **`runPluginVerifier`** against a small IDE matrix (at least target `2025.2` + one newer EAP/stable).
3. Add detekt or ktlint (single tool; keep config minimal).
4. Expand unit tests (priority order):
   - `FileOperationsService` (path traversal, symlink)
   - `IgnoredFilesService` (porcelain parsing)
   - `GitWorktreeService` branch resolution / merge-pull-push error mapping
   - create+copy await behavior
5. Optional: Dependabot or Gradle version catalog.
6. Release dry-run (`publish=false`) should not require publish token until the publish step.

### Exit criteria

- [ ] Verifier job green on PR
- [ ] Platform plugin on a current 2.x release
- [ ] New service tests cover the “Discovered Work” items in `docs/hyperpowers/current-progress.md`

---

## Phase 6 — Docs & contributor hygiene (P6)

**Goal:** External docs match the product; no personal scratch in README.

1. Clean README: remove personal `JAVA_HOME` scratch; keep proxy as optional tip.
2. Soften architecture claims (“pure composables”) to match reality after Phase 3.
3. Refresh `TESTING_CHECKLIST.md` to match current Create dialog (checkbox, not Yes/No prompt).
4. Add brief `CONTRIBUTING.md` (build, `runIde`, PR CI expectations).
5. Archive or mark `docs/hyperpowers/*` as internal historical notes; fix the “0 security vulnerabilities” claim after Phase 0.
6. Document unsigned CI artifact vs signed Marketplace zip (already partly in runbook).

---

## Suggested execution order (dependency graph)

```text
Phase 0 (telemetry) ─────────────────────────────┐
Phase 1 (correctness/packaging) ─────────────────┼─► Phase 3 (UI split/polish)
Phase 2 (ship or hide features) ─────────────────┘         │
                                                           ▼
                                                    Phase 4 (i18n/listing)
                                                           │
Phase 5 (verifier/deps/tests) ◄── can start in parallel after Phase 1
Phase 6 (docs) ◄── continuous; finalize after Phase 0–2 decisions
```

**Do not** start large UI refactors before Phase 1B (copy race) and Phase 2 (feature decisions), or you will move dead code.

---

## Out of scope (for this roadmap)

- Rewriting the plugin in pure Swing
- Multi-VCS support beyond Git
- Full automated UI (Compose) screenshot tests (nice-to-have later)
- Changing Marketplace pricing / branding identity

---

## First concrete PR slice (recommended kickoff)

If implementing immediately, smallest high-value sequence:

1. **PR-A:** Telemetry key removal + disable-by-default / opt-in stub  
2. **PR-B:** Hard Compose dependency + await copy-on-create  
3. **PR-C:** Wire or remove Agent Context + RemoteBranch dialogs  
4. **PR-D:** `runPluginVerifier` + platform Gradle plugin bump  

Each PR should stay reviewable (< ~400 LOC where possible for A–C; D may be larger due to lockfiles).

---

## Success metrics

| Signal | Target |
|--------|--------|
| Secrets in binary | None |
| `MyToolWindow.kt` size | Split; factory < 100 LOC; screens modular |
| i18n coverage of UI strings | ~100% of user-facing English in bundle |
| CI | test + structure + buildPlugin + verifier |
| Dead feature folders | Zero unreachable dialogs/services |
| Marketplace listing | Matches shipped features |

---

## Appendix — Key file map

| Path | Role in plan |
|------|----------------|
| `services/TelemetryService.kt` | Phase 0 |
| `viewmodel/WorktreeViewModel.kt` | Phase 1B, 2 |
| `META-INF/plugin.xml` / `compose.xml` | Phase 1A, 4 |
| `MyToolWindow.kt` | Phase 3 |
| `ui/dialogs/*` | Phase 2, 4 |
| `messages/MyMessageBundle.properties` | Phase 4 |
| `build.gradle.kts` / `.github/workflows/*` | Phase 5 |
| `README.md` / `docs/*` | Phase 6 |
