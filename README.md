# Git Worktree Manager

A powerful IntelliJ IDEA/Android Studio plugin that simplifies Git worktree management, allowing you to work on multiple features simultaneously without the overhead of switching branches or maintaining multiple clones.

## Features

- **Create Worktrees**: Create new worktrees with a simple dialog interface (new branch, existing local branch, or remote branch)
- **Copy Ignored Files**: Optionally copy gitignored files (build artifacts, dependencies, etc.) to new worktrees
- **Copy Claude Code Context**: Optionally copy `.claude` project context / session history into new worktrees
- **Push to Remote**: Push a worktree branch to origin with one click
- **Merge / Pull / Prune**: Merge into another worktree, pull/push, and prune stale worktrees from the tool window
- **Copy Actions**: Quickly copy worktree path/branch/commit to clipboard
- **Open in Terminal**: Open a worktree directory in the IDE terminal (requires the Terminal plugin)
- **Reveal in Explorer**: Reveal a worktree directory in the OS file manager
- **Auto-Open in New Window**: Automatically opens newly created worktrees in a separate IDE window
- **Delete Worktrees**: Remove worktrees with one click (with confirmation dialog)
- **Modern UI**: Built with Jetpack Compose for a native, responsive user experience

## Why Use Git Worktrees?

Git worktrees allow you to check out multiple branches from the same repository into separate directories. This is especially useful for Android developers who need to:

- Work on multiple features simultaneously
- Quickly switch between bug fixes and feature development
- Run parallel CI/CD pipelines
- Compare implementations across branches
- Keep Gradle build caches separate (faster builds!)

Read more about the benefits: [Increase Productivity with Git Worktrees](https://medium.com/@domen.lanisnik/increase-productivity-with-git-worktrees-as-an-android-developer-c7e8b99eeab5)

## Installation

### From CI artifact (after merge to master)

After code is merged to `master`, GitHub Actions builds an installable zip:

1. Open **Actions** → latest green **CI** run on `master`
2. Download the **Artifacts** zip (`git-worktree-manager-<sha>`)
3. In the IDE: **Settings → Plugins → ⚙️ → Install Plugin from Disk…**

### From Source

1. Clone this repository
2. (Optional) Set proxy environment variables if you are behind a firewall:
   - `https_proxy=http://127.0.0.1:7897`
   - `http_proxy=http://127.0.0.1:7897`
   - `all_proxy=socks5://127.0.0.1:7897`
3. Run `./gradlew buildPlugin` (or `./gradlew build`)
4. Install the plugin from `build/distributions/git-worktree-manager-*.zip`

set JAVA_HOME=D:\jdk\jdk-21.0.7
$env:JAVA_HOME = "D:\jdk\jdk-21.0.7"    powershell
./gradlew.bat build -Dorg.gradle.java.home=D:\jdk\jdk-21.0.7

### From Marketplace 

Search for "Git Worktree Manager" in the IntelliJ Plugin Marketplace.
https://plugins.jetbrains.com/plugin/29905-git-worktree-manager?noRedirect=true

## Usage

### Opening the Tool Window

1. Open your project in IntelliJ IDEA or Android Studio
2. Look for the "Git Worktrees" tool window (usually in the bottom panel)
3. Click to expand the tool window

### Creating a Worktree

1. Click the "Create Worktree" button
2. Fill the dialog:
   - **Worktree name**: e.g., "feature-auth"
   - **Branch name**: e.g., "feature/auth"
   - **Create new branch**:
     - **Enabled**: create and checkout a new local branch
     - **Disabled**: use an existing local branch (or pick a remote branch if the local name is missing)
   - **Copy ignored files** (optional): scan `.gitignore` and let you select files to copy
   - **Copy Claude Code context** (optional): copy `.claude` project context / session history when present
3. The plugin will:
   - Create the worktree in the parent directory (e.g., `../myproject-feature-auth`)
   - Create or reuse the branch based on the selected mode
   - Copy selected ignored files (if requested)
   - Automatically open the worktree in a new IDE window

**Note**: Copying ignored files can save significant time by avoiding expensive operations like re-downloading dependencies or rebuilding artifacts.

### Deleting a Worktree

1. Find the worktree in the list
2. Click the "Delete" button
3. Confirm the deletion
4. The worktree will be removed from disk

## How It Works

The plugin creates worktrees following the recommended pattern:

```
parent-directory/
├── my-project/          # Main repository
├── my-project-feature-a/ # Worktree for feature A
└── my-project-feature-b/ # Worktree for feature B
```

Each worktree:
- Shares the same Git history (no duplication)
- Has its own working directory
- Maintains separate Gradle build caches
- Can be opened in a separate IDE window

## Requirements

- IntelliJ IDEA 2025.2+ or Android Studio with equivalent platform version
- Git 2.5+ (for worktree support)
- Git 2.11.0+ recommended (for ignored files detection feature)
- Terminal plugin is required for the "Open in Terminal" action (it is bundled in most IDE distributions)

## Architecture

This plugin follows clean architecture principles with clear separation of concerns:

### Layer Structure

```
UI Layer (Composables)
    ↓
Presentation Layer (ViewModel)
    ↓
Data Layer (Repository)
    ↓
Platform Services (GitWorktreeService)
```

### Components

- **WorktreeState**: Immutable data class representing UI state (worktrees list, loading state, errors, ignored files)
- **WorktreeRepository**: Data access layer that wraps GitWorktreeService and handles Git repository operations
- **WorktreeViewModel**: Presentation logic layer that manages state and coordinates between Repository and UI (uses constructor dependency injection)
- **IgnoredFilesService**: Project-level service for detecting files ignored by `.gitignore` using Git commands
- **FileOperationsService**: Project-level service for securely copying files and directories between worktrees
- **Pure Composables**: UI components with no dependencies on IntelliJ Platform APIs, making them testable and maintainable
- **DialogWrapper Dialogs**: Native IntelliJ dialogs for file selection and copy results

### Design Principles

- **Separation of Concerns**: UI, presentation logic, and data access are clearly separated
- **Dependency Inversion**: UI depends on abstractions (callbacks) rather than concrete implementations
- **JetBrains Best Practices**: Follows official IntelliJ Platform patterns for service management and dependency injection
- **Testability**: Pure UI functions can be tested with mock data, ViewModels can be tested independently

This architecture makes the codebase maintainable, testable, and follows IntelliJ Platform best practices for plugin development.

## Development

This plugin is built with:
- **Kotlin**: Primary programming language
- **IntelliJ Platform SDK**: Plugin framework
- **Jetpack Compose for Desktop (Jewel)**: Modern declarative UI
- **Git4Idea**: IntelliJ's Git integration API
- **Coroutines**: Asynchronous operations and state management
- **Clean Architecture**: ViewModel + Repository pattern for separation of concerns

### Building

```bash
./gradlew build
```

### Running

```bash
./gradlew runIde
```

## Support

If this plugin saves you time, consider sponsoring to help me maintain it — you’ll be buying my cats a treat.

Support link: https://github.com/sponsors/MingLu0

## License

MIT License - See LICENSE file for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Author

Built with ❤️ by PurringLabs - Ming Luo 

## Acknowledgments

- Inspired by [this article](https://medium.com/@domen.lanisnik/increase-productivity-with-git-worktrees-as-an-android-developer-c7e8b99eeab5) on Git worktrees for Android developers
- Built with [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
