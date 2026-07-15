# AGENTS.md

## Cursor Cloud specific instructions

This repository is a single **IntelliJ IDEA / Android Studio plugin** ("Git Worktree Manager"),
written in **Kotlin** and built with **Gradle** (Kotlin DSL). There is no web server, database, or
container to run — the "application" is the plugin loaded into a sandboxed IDE.

### Toolchain (already provided by the environment)
- **JDK 21** is installed and on `PATH` (Gradle uses it automatically; `JAVA_HOME` is not required).
- **Git 2.43** is installed. The plugin shells out to the system `git` binary for all worktree operations.
- Use the Gradle wrapper `./gradlew` (pins Gradle 9.0.0). Do not install Gradle separately.
- The first Gradle build downloads the **IntelliJ IDEA 2025.2.4** distribution (~large, slow once);
  it is cached under `~/.gradle` afterward.

### Build / test / run commands
- Build the plugin (compiles main source, assembles the ZIP under `build/distributions/`):
  `./gradlew buildPlugin`
- Plugin structure check (the CI static/validation gate): `./gradlew verifyPluginStructure`
- Run the test suite: `./gradlew test` (see known issue below).
- Full CI-equivalent: `./gradlew test verifyPluginStructure` (this is what `.github/workflows/ci.yml` runs).
- `./gradlew build` runs assemble + `test`; it therefore inherits the test-compile issue below.
- There is **no dedicated lint task** (no ktlint/detekt configured); `verifyPluginStructure` is the
  closest static-analysis gate.

### Known issue: test suite does not compile on `master`
`src/test/.../viewmodel/WorktreeViewModelProgressTest.kt` defines a `FakeWorktreeRepository` that
implements `WorktreeRepositoryContract`, but it is **missing four methods** that were later added to
the interface (`mergeBranchInto`, `pullBranch`, `pushBranch`, `pruneWorktrees`). As a result
`./gradlew test` (and `./gradlew build`) fail at `compileTestKotlin` on a clean checkout. This is a
pre-existing code defect, not an environment problem. Adding the four missing `override` stubs to the
fake makes all 30 tests pass. Fix the source if you need the tests to run; `buildPlugin` and
`verifyPluginStructure` are unaffected.

### Running the plugin end-to-end (`runIde`)
- `./gradlew runIde` launches a **GUI** sandbox IDE, so it needs a display. In Cursor Cloud a display
  is available at `DISPLAY=:1` (Xvfb), so run: `DISPLAY=:1 ./gradlew runIde`.
- On first launch the sandbox IDE shows a **User Agreement** and a **Data Sharing** dialog that must be
  accepted/dismissed before the Welcome screen appears.
- To exercise the plugin you must open a project that is a **Git repository** (e.g. `git init` a temp
  folder with at least one commit). The plugin's tool window is **"Git Worktrees"**, anchored at the
  **bottom** of the IDE. Use its "Create Worktree" button to create worktrees; newly created worktrees
  are auto-opened in a new IDE window.
- Sandbox IDE logs: `build/idea-sandbox/**/log/idea.log`.
