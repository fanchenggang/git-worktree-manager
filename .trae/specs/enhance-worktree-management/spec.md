# Git Worktree 管理增强功能规范 (Spec)

## 为什么需要此功能
目前插件提供了基础的 Git Worktree 生命周期管理（创建、删除、打开、合并）。然而，开发者经常需要清理失效的 Worktree 引用（Prune），通过终端（Terminal）或文件资源管理器（Explorer）访问 Worktree 目录，以及直接从工具窗口同步远程仓库（Pull）。基于现有功能增加这些增强特性，将显著提升开发者的工作效率，并提供更完整的 Worktree 管理体验。

## 变更内容
- 在顶部操作栏的“Refresh”按钮旁添加“Prune”按钮，用于清理无效的 Worktree 引用。
- 扩展每个 Worktree 项的右键上下文菜单，新增以下功能：
  - "Open in Terminal"（在终端中打开）：在当前工作树路径打开 IDE 内置终端。
  - "Reveal in Explorer"（在资源管理器中显示）：在系统文件管理器中打开该工作树目录。
  - "Pull from remote"（从远程拉取）：从 origin 拉取该工作树对应分支的最新代码。
- 在 `GitWorktreeService` 和 `WorktreeRepository` 中添加 `pruneWorktrees` 方法。
- 更新 `WorktreeViewModel` 以处理 Prune 和 Pull 操作，并暴露这些操作的状态。

## 影响范围
- 受影响的功能：工具窗口 UI，视图模型 (ViewModels)，Git 工作树服务 (GitWorktreeService)。
- 受影响的代码文件：
  - `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/MyToolWindow.kt`
  - `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/viewmodel/WorktreeViewModel.kt`
  - `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/viewmodel/WorktreeState.kt`
  - `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/repository/WorktreeRepository.kt`
  - `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/repository/WorktreeRepositoryContract.kt`
  - `src/main/kotlin/com/purringlabs/gitworktree/gitworktreemanager/services/GitWorktreeService.kt`

## 新增需求
### 需求：清理无效工作树 (Prune Worktrees)
系统应提供执行 `git worktree prune` 命令的途径。
#### 场景：成功清理
- **当** 用户点击 "Prune" 按钮时
- **则** 系统执行 `git worktree prune` 命令，移除失效的条目，并刷新工作树列表。

### 需求：在终端中打开 (Open in Terminal)
系统应在上下文菜单中提供在 IDE 终端打开工作树的操作。
#### 场景：成功打开
- **当** 用户右键点击某个工作树并选择 "Open in Terminal" 时
- **则** IDE 打开一个新的内置终端会话，且工作目录设置为该工作树路径。

### 需求：在资源管理器中显示 (Reveal in Explorer)
系统应在上下文菜单中提供在系统文件管理器中显示工作树目录的操作。
#### 场景：成功显示
- **当** 用户右键点击某个工作树并选择 "Reveal in Explorer" 时
- **则** 系统原生的文件资源管理器将打开该工作树所在的目录。

### 需求：从远程拉取 (Pull from Remote)
系统应在上下文菜单中提供拉取工作树分支最新代码的操作。
#### 场景：成功拉取
- **当** 用户右键点击某个工作树并选择 "Pull from remote" 时
- **则** 系统在后台为该分支执行 git pull 操作，并显示成功或错误的通知。
