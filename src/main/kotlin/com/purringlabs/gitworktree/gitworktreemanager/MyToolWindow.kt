package com.purringlabs.gitworktree.gitworktreemanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.awt.RelativePoint
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepository
import com.purringlabs.gitworktree.gitworktreemanager.services.ClaudeCodeContextService
import com.purringlabs.gitworktree.gitworktreemanager.services.FileOperationsService
import com.purringlabs.gitworktree.gitworktreemanager.services.GitWorktreeService
import com.purringlabs.gitworktree.gitworktreemanager.services.IgnoredFilesService
import com.purringlabs.gitworktree.gitworktreemanager.services.NoRepositoryUiHelper
import com.purringlabs.gitworktree.gitworktreemanager.services.UiErrorMapper
import com.purringlabs.gitworktree.gitworktreemanager.ui.WorktreeListScreen
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.AgentContextCopyDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.AgentContextCopyResultDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.CopyResultDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.CreateWorktreeDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.ErrorDetailsDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.IgnoredFilesSelectionDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.MergeIntoBranchDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.RemoteBranchSelectionDialog
import com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeViewModel
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.plugins.terminal.TerminalView
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Paths
import javax.swing.Icon

private fun showOperationError(project: Project, error: Throwable, operation: String) {
    val uiError = UiErrorMapper.map(error, operation)
    ErrorDetailsDialog(
        project = project,
        titleText = uiError.title,
        summary = uiError.summary,
        actions = uiError.actions,
        detailsText = uiError.details,
        copyText = uiError.copyText
    ).show()
}

private fun findValidRepository(project: Project): GitRepository? {
    return GitRepositoryManager.getInstance(project)
        .repositories
        .firstOrNull { repo -> File(repo.root.path).exists() }
}

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab("Git Worktrees", focusOnClickInside = true) {
            WorktreeManagerContent(project)
        }
    }
}

/**
 * Wrapper composable that holds the Project reference and manages the ViewModel
 * This is the only composable that knows about Project and IntelliJ Platform APIs
 */
@Composable
private fun WorktreeManagerContent(project: Project) {
    // UI scope: safe for UI interactions within the current composition.
    val uiScope = rememberCoroutineScope()

    // ViewModel scope: must outlive the composition to avoid ForgottenCoroutineScopeException when
    // long-running Git operations complete after the UI leaves composition.
    // Use a single-threaded dispatcher to serialize state updates (state = state.copy(...))
    // while still allowing repository methods to hop to Dispatchers.IO internally.
    val viewModelScope = remember(project) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    }

    val viewModel = remember(project) {
        WorktreeViewModel(
            project = project,
            coroutineScope = viewModelScope,
            repository = WorktreeRepository(project),
            ignoredFilesService = IgnoredFilesService.getInstance(project),
            fileOpsService = FileOperationsService.getInstance(project)
        )
    }

    // Initialize data on first composition
    LaunchedEffect(project) {
        viewModel.refreshWorktrees()
    }

    DisposableEffect(project) {
        val disposable = registerGitRepoAutoRefresh(
            project = project,
            requestAutoRefresh = viewModel::requestAutoRefresh,
            cancelAutoRefresh = viewModel::cancelAutoRefresh
        )
        onDispose {
            // Stop background jobs when the tool window content is disposed.
            viewModelScope.cancel()
            Disposer.dispose(disposable)
        }
    }

    val onCreateWorktree: (String, String, Boolean) -> Unit = { name, branch, createNewBranch ->
        viewModel.createWorktree(
            name = name,
            branchName = branch,
            createNewBranch = createNewBranch,
            onSuccess = { createResult ->
                ApplicationManager.getApplication().invokeLater {
                    // Open the worktree in a new window
                    ProjectUtil.openOrImport(File(createResult.path).toPath(), project, true)
                    Messages.showInfoMessage(
                        project,
                        if (createResult.created) {
                            "Worktree created and opened in new window!"
                        } else {
                            "Worktree already exists — opened existing worktree in new window."
                        },
                        "Success"
                    )
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(project, error, operation = "CREATE_WORKTREE")
                }
            }
        )
    }

    val onCreateWorktreeWithIgnoredFiles: (String, String, Boolean) -> Unit = { name, branch, createNewBranch ->
        uiScope.launch {
            // Step 1: Scan for ignored files
            viewModel.scanIgnoredFiles()

            // Step 2: Check for scan errors
            if (viewModel.state.scanError != null) {
                withContext(Dispatchers.Main) {
                    Messages.showErrorDialog(
                        project,
                        "Failed to scan ignored files: ${viewModel.state.scanError}",
                        "Error"
                    )
                }
                return@launch
            }

            // Step 3: Show selection dialog
            val ignoredFiles = viewModel.state.ignoredFiles
            if (ignoredFiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Messages.showInfoMessage(
                        project,
                        "No ignored files found.",
                        "No Ignored Files"
                    )
                }
                // Still create the worktree without copying files
                onCreateWorktree(name, branch, createNewBranch)
                return@launch
            }

            // Show dialog on main thread
            val selectedFiles = withContext(Dispatchers.Main) {
                val dialog = IgnoredFilesSelectionDialog(project, ignoredFiles)
                if (dialog.showAndGet()) {
                    dialog.getSelectedFiles()
                } else {
                    null // User cancelled
                }
            }

            // Step 4: Create worktree with or without selected files
            if (selectedFiles != null) {
                // User selected files - create worktree with copying
                viewModel.createWorktreeWithIgnoredFiles(
                    worktreeName = name,
                    branchName = branch,
                    createNewBranch = createNewBranch,
                    selectedFiles = selectedFiles,
                    onSuccess = { createResult, copyResult ->
                        ApplicationManager.getApplication().invokeLater {
                            // Open the worktree in a new window
                            ProjectUtil.openOrImport(File(createResult.path).toPath(), project, true)

                            // Show copy results if available (copy completed before this callback)
                            if (copyResult != null && (copyResult.successCount > 0 || copyResult.failed.isNotEmpty())) {
                                val resultDialog = CopyResultDialog(project, copyResult)
                                resultDialog.show()
                            }

                            Messages.showInfoMessage(
                                project,
                                if (createResult.created) {
                                    "Worktree created and opened in new window!"
                                } else {
                                    "Worktree already exists — opened existing worktree in new window."
                                },
                                "Success"
                            )
                        }
                    },
                    onError = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            showOperationError(project, error, operation = "CREATE_WORKTREE")
                        }
                    }
                )
            } else {
                // User cancelled selection - create worktree without copying files
                onCreateWorktree(name, branch, createNewBranch)
            }
        }
    }

    val onCreateWorktreeWithAgentContext: (String, String, Boolean) -> Unit = { name, branch, createNewBranch ->
        uiScope.launch {
            val repository = findValidRepository(project)
            if (repository == null) {
                withContext(Dispatchers.Main) {
                    NoRepositoryUiHelper.showNoRepositoryDialog(project, "CREATE_WORKTREE")
                }
                return@launch
            }

            val gitWorktreeService = GitWorktreeService.getInstance(project)
            val destinationPath = gitWorktreeService.getWorktreePath(repository, name)
            val options = ClaudeCodeContextService.getInstance(project).detectCopyOptions(
                sourceRepoPath = Paths.get(repository.root.path),
                destinationWorktreePath = Paths.get(destinationPath)
            )

            if (options.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Messages.showInfoMessage(
                        project,
                        "No Claude Code context found to copy.",
                        "No Agent Context"
                    )
                }
                onCreateWorktree(name, branch, createNewBranch)
                return@launch
            }

            val selectedOptions = withContext(Dispatchers.Main) {
                val dialog = AgentContextCopyDialog(project, options)
                if (dialog.showAndGet()) dialog.selectedOptions() else null
            }

            if (selectedOptions == null) {
                onCreateWorktree(name, branch, createNewBranch)
                return@launch
            }

            viewModel.createWorktreeWithAgentContext(
                worktreeName = name,
                branchName = branch,
                createNewBranch = createNewBranch,
                selectedOptions = selectedOptions,
                onSuccess = { createResult, copyResult ->
                    ApplicationManager.getApplication().invokeLater {
                        ProjectUtil.openOrImport(File(createResult.path).toPath(), project, true)
                        if (copyResult != null && (copyResult.copiedCount > 0 || copyResult.failureCount > 0 || copyResult.skippedCount > 0)) {
                            AgentContextCopyResultDialog(project, copyResult).show()
                        }
                        Messages.showInfoMessage(
                            project,
                            if (createResult.created) {
                                "Worktree created and opened in new window!"
                            } else {
                                "Worktree already exists — opened existing worktree in new window."
                            },
                            "Success"
                        )
                    }
                },
                onError = { error ->
                    ApplicationManager.getApplication().invokeLater {
                        showOperationError(project, error, operation = "CREATE_WORKTREE")
                    }
                }
            )
        }
    }

    val onValidateWorktreeName: (String) -> String? = { initialName ->
        val repository = findValidRepository(project)
        if (repository != null) {
            val gitWorktreeService = GitWorktreeService.getInstance(project)
            var name = initialName

            var result: String? = null
            var keepValidating = true
            while (keepValidating) {
                val path = gitWorktreeService.getWorktreePath(repository, name)
                val existingDir = File(path)
                if (!existingDir.exists()) {
                    result = name
                    keepValidating = false
                } else {
                    val existingWorktree = try {
                        listWorktreesInBackground(project, repository)
                            ?.firstOrNull { it.path == path }
                    } catch (_: ProcessCanceledException) {
                        keepValidating = false
                        null
                    }

                    if (keepValidating) {
                        val choice = Messages.showDialog(
                            project,
                            "The worktree folder already exists:\n$path\n\nWhat would you like to do?",
                            "Worktree Folder Already Exists",
                            arrayOf("Open existing", "Use another name…", "Cancel"),
                            0,
                            Messages.getWarningIcon()
                        )

                        when (choice) {
                            0 -> {
                                if (existingWorktree != null) {
                                    openOrFocusWorktree(project, existingWorktree.path)
                                } else {
                                    Messages.showErrorDialog(
                                        project,
                                        "Folder exists but is not a registered git worktree. Please choose another name or remove the folder manually.\n\n$path",
                                        "Cannot Open Existing Folder"
                                    )
                                }
                                keepValidating = false
                            }
                            1 -> {
                                val newName = Messages.showInputDialog(
                                    project,
                                    "Enter a different worktree name:",
                                    "Choose Another Name",
                                    Messages.getQuestionIcon(),
                                    "${name}-2",
                                    null
                                )

                                if (newName.isNullOrBlank()) {
                                    keepValidating = false
                                } else {
                                    name = newName
                                }
                            }
                            else -> keepValidating = false
                        }
                    }
                }
            }
            result
        } else {
            initialName
        }
    }

    val onValidateBranchName: (String) -> String? = { initialBranch ->
        val repository = findValidRepository(project)
        if (repository != null) {
            var branchName = sanitizeBranchName(initialBranch)

            fun branchAlreadyExists(name: String): Boolean {
                return repository.branches.localBranches.any { it.name == name }
            }

            var result: String? = null
            var keepValidating = true
            while (keepValidating) {
                if (branchName.isBlank()) {
                    val newBranch = Messages.showInputDialog(
                        project,
                        "Branch name is empty after sanitization. Please enter a valid name.",
                        "Invalid Branch Name",
                        Messages.getWarningIcon(),
                        null,
                        null
                    )

                    if (newBranch.isNullOrBlank()) {
                        keepValidating = false
                    } else {
                        branchName = sanitizeBranchName(newBranch)
                    }
                } else if (!branchAlreadyExists(branchName)) {
                    result = branchName
                    keepValidating = false
                } else {
                    val choice = Messages.showDialog(
                        project,
                        "Branch '$branchName' already exists. Please choose another name.",
                        "Branch Already Exists",
                        arrayOf("Use another name…", "Cancel"),
                        0,
                        Messages.getWarningIcon()
                    )

                    when (choice) {
                        0 -> {
                            val newBranch = Messages.showInputDialog(
                                project,
                                "Enter a new branch name:",
                                "Choose Another Branch Name",
                                Messages.getQuestionIcon(),
                                "${branchName}-2",
                                null
                            )

                            if (newBranch.isNullOrBlank()) {
                                keepValidating = false
                            } else {
                                branchName = sanitizeBranchName(newBranch)
                            }
                        }
                        else -> keepValidating = false
                    }
                }
            }
            result
        } else {
            initialBranch
        }
    }

    fun copyToClipboard(label: String, value: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(value))
        PopupUtil.showBalloonForActiveComponent("$label copied", MessageType.INFO)
    }

    val currentProjectBasePath = remember(project) { project.basePath }

    val onConfirmDelete: (WorktreeInfo) -> Boolean = { worktree ->
        val result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete this worktree?\n${worktree.path}",
            "Delete Worktree",
            Messages.getWarningIcon()
        )
        result == Messages.YES
    }

    val onDeleteWorktree: (WorktreeInfo) -> Unit = { worktree ->
        viewModel.deleteWorktree(
            worktreePath = worktree.path,
            onSuccess = { result ->
                ApplicationManager.getApplication().invokeLater {
                    if (result.branchDeleted) {
                        PopupUtil.showBalloonForActiveComponent("Worktree deleted", MessageType.INFO)
                    } else {
                        val reason = result.branchDeleteError?.gitErrorOutput
                            ?: result.branchDeleteError?.errorMessage
                            ?: "Unknown reason"
                        Messages.showWarningDialog(
                            project,
                            "Worktree removed, but branch cleanup failed.\n\nReason: $reason",
                            "Partial Success"
                        )
                    }
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(project, error, operation = "DELETE_WORKTREE")
                }
            }
        )
    }

    val onMergeIntoBranch: (WorktreeInfo) -> Unit = onMergeIntoBranch@{ sourceWorktree ->
        val sourceBranch = sourceWorktree.branch
        if (sourceBranch.isNullOrBlank()) return@onMergeIntoBranch
        val targetWorktrees = viewModel.state.worktrees
            .filter { it.path != sourceWorktree.path && it.branch != null }
            .sortedByDescending { it.branch?.lowercase() == "develop" }
        if (targetWorktrees.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No other worktree with a branch to merge into.",
                "Merge"
            )
            return@onMergeIntoBranch
        }
        val dialog = MergeIntoBranchDialog(project, sourceBranch, targetWorktrees)
        if (dialog.showAndGet()) {
            val target = dialog.getSelectedTarget()
            if (target != null) {
                val targetBranch = target.branch!!
                viewModel.mergeBranchInto(
                    sourceBranch = sourceBranch,
                    targetWorktreePath = target.path,
                    targetBranch = targetBranch,
                    onSuccess = {
                        ApplicationManager.getApplication().invokeLater {
                            val choice = Messages.showDialog(
                                project,
                                "Merged \"$sourceBranch\" into $targetBranch successfully.\n\nPush to remote?",
                                "Merge Success",
                                arrayOf("Cancel", "Push"),
                                1,
                                Messages.getQuestionIcon()
                            )
                            if (choice == 1) {
                                viewModel.pushBranch(
                                    worktreePath = target.path,
                                    branchName = targetBranch,
                                    onSuccess = {
                                        ApplicationManager.getApplication().invokeLater {
                                            PopupUtil.showBalloonForActiveComponent("Pushed $targetBranch", MessageType.INFO)
                                        }
                                    },
                                    onError = { error ->
                                        ApplicationManager.getApplication().invokeLater {
                                            showOperationError(project, error, operation = "PUSH_BRANCH")
                                        }
                                    }
                                )
                            }
                        }
                    },
                    onError = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            showOperationError(project, error, operation = "MERGE_BRANCH")
                        }
                    }
                )
            }
        }
    }

    val onPullFromRemote: (WorktreeInfo) -> Unit = onPullFromRemote@{ worktree ->
        val branch = worktree.branch ?: return@onPullFromRemote
        viewModel.pullBranch(
            worktreePath = worktree.path,
            branchName = branch,
            onSuccess = {
                ApplicationManager.getApplication().invokeLater {
                    PopupUtil.showBalloonForActiveComponent("Pulled $branch", MessageType.INFO)
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(project, error, operation = "PULL_BRANCH")
                }
            }
        )
    }

    val onPushToRemote: (WorktreeInfo) -> Unit = onPushToRemote@{ worktree ->
        val branch = worktree.branch ?: return@onPushToRemote
        viewModel.pushToRemote(
            worktreePath = worktree.path,
            branchName = branch,
            onSuccess = {
                ApplicationManager.getApplication().invokeLater {
                    PopupUtil.showBalloonForActiveComponent("Pushed $branch", MessageType.INFO)
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(project, error, operation = "PUSH_BRANCH")
                }
            }
        )
    }

    val onOpenInTerminal: (WorktreeInfo) -> Unit = { worktree ->
        ApplicationManager.getApplication().invokeLater {
            try {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(worktree.path)
                if (virtualFile != null) {
                    TerminalView.getInstance(project).openTerminalIn(virtualFile)
                } else {
                    Messages.showErrorDialog(project, "Could not find worktree directory.", "Error")
                }
            } catch (e: NoClassDefFoundError) {
                Messages.showErrorDialog(project, "Terminal plugin is not available.", "Error")
            } catch (e: Exception) {
                Messages.showErrorDialog(project, "Failed to open terminal: ${e.message}", "Error")
            }
        }
    }

    val onRevealInExplorer: (WorktreeInfo) -> Unit = { worktree ->
        ApplicationManager.getApplication().invokeLater {
            RevealFileAction.openDirectory(File(worktree.path))
        }
    }

    val onCopyPath: (WorktreeInfo) -> Unit = { worktree ->
        copyToClipboard(label = "Path", value = worktree.path)
    }

    val onCopyBranch: (WorktreeInfo) -> Unit = { worktree ->
        val branch = worktree.branch
        if (branch != null) {
            copyToClipboard(label = "Branch", value = branch)
        } else {
            PopupUtil.showBalloonForActiveComponent("No branch to copy", MessageType.INFO)
        }
    }

    val onCopyCommit: (WorktreeInfo) -> Unit = { worktree ->
        copyToClipboard(label = "Commit", value = worktree.commit)
    }

    val onShowContextMenu: (WorktreeInfo, Offset) -> Unit = onShowContextMenu@{ worktree, offset ->
        val component = IdeFocusManager.getInstance(project).focusOwner ?: return@onShowContextMenu
        val dataContext: DataContext = DataManager.getInstance().getDataContext(component)

        val st = viewModel.state
        val branch = worktree.branch
        val isCurrent = isCurrentWorktree(currentProjectBasePath = currentProjectBasePath, worktreePath = worktree.path)
        val deleteEnabled = isDeleteEnabled(isMain = worktree.isMain, isCurrent = isCurrent, isDeleting = st.deletingWorktreePath == worktree.path)
        val busy = st.isCreating ||
            st.isScanning ||
            st.isPruning ||
            st.deletingWorktreePath != null ||
            st.mergingSourceBranch != null ||
            st.pushingBranch != null ||
            st.pullingBranch != null

        fun makeAction(
            text: String,
            icon: Icon,
            visible: Boolean = true,
            enabled: Boolean = true,
            run: () -> Unit
        ): AnAction {
            return object : AnAction(text, null, icon) {
                override fun actionPerformed(e: AnActionEvent) {
                    run()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = visible
                    e.presentation.isEnabled = enabled
                }
            }
        }

        val group = DefaultActionGroup().apply {
            add(makeAction("Merge into other branch...", AllIcons.Vcs.Merge, visible = branch != null, enabled = !busy && branch != null) {
                onMergeIntoBranch(worktree)
            })
            add(makeAction("Pull from remote", AllIcons.Actions.Refresh, visible = branch != null, enabled = !busy && branch != null) {
                onPullFromRemote(worktree)
            })
            add(makeAction("Push to remote", AllIcons.Vcs.Push, visible = branch != null, enabled = !busy && branch != null) {
                onPushToRemote(worktree)
            })
            addSeparator()
            add(makeAction("Open in Terminal", AllIcons.Nodes.Console, enabled = !busy) {
                onOpenInTerminal(worktree)
            })
            add(makeAction("Reveal in Explorer", AllIcons.Nodes.Folder, enabled = !busy) {
                onRevealInExplorer(worktree)
            })
            addSeparator()
            add(makeAction("Copy path", AllIcons.Actions.Copy, enabled = true) {
                onCopyPath(worktree)
            })
            add(makeAction("Copy branch", AllIcons.Actions.Copy, visible = branch != null, enabled = branch != null) {
                onCopyBranch(worktree)
            })
            add(makeAction("Copy commit", AllIcons.Actions.Copy, enabled = true) {
                onCopyCommit(worktree)
            })
            addSeparator()
            add(makeAction("Delete worktree", AllIcons.General.Remove, enabled = !busy && deleteEnabled) {
                if (onConfirmDelete(worktree)) {
                    onDeleteWorktree(worktree)
                }
            })
        }

        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null,
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        )
        popup.show(RelativePoint(component, Point(offset.x.toInt(), offset.y.toInt())))
    }

    WorktreeListScreen(
        state = viewModel.state,
        currentProjectBasePath = currentProjectBasePath,
        onSearchQueryChange = viewModel::setSearchQuery,
        onRefresh = {
            val repository = findValidRepository(project)
            if (repository == null) {
                NoRepositoryUiHelper.showNoRepositoryDialog(
                    project = project,
                    attemptedOperation = "LIST_WORKTREES"
                )
                return@WorktreeListScreen
            }
            viewModel.refreshWorktrees()
        },
        onPrune = {
            val repository = findValidRepository(project)
            if (repository == null) {
                NoRepositoryUiHelper.showNoRepositoryDialog(
                    project = project,
                    attemptedOperation = "PRUNE_WORKTREES"
                )
                return@WorktreeListScreen
            }
            viewModel.pruneWorktrees(
                onSuccess = {
                    ApplicationManager.getApplication().invokeLater {
                        PopupUtil.showBalloonForActiveComponent("Pruned stale worktrees", MessageType.INFO)
                    }
                },
                onError = { error ->
                    ApplicationManager.getApplication().invokeLater {
                        showOperationError(project, error, operation = "PRUNE_WORKTREES")
                    }
                }
            )
        },
        onOpenWorktree = { worktree ->
            openOrFocusWorktree(project, worktree.path)
        },
        onShowContextMenu = onShowContextMenu,
        onCreateWorktreeRequest = {
            val repository = findValidRepository(project)
            if (repository == null) {
                NoRepositoryUiHelper.showNoRepositoryDialog(
                    project = project,
                    attemptedOperation = "CREATE_WORKTREE"
                )
                return@WorktreeListScreen
            }

            val dialog = CreateWorktreeDialog(project)
            if (dialog.showAndGet()) {
                val rawName = dialog.getWorktreeName()
                val rawBranchName = dialog.getBranchName()
                val createNewBranch = dialog.shouldCreateNewBranch()
                val copyIgnoredFiles = dialog.shouldCopyIgnoredFiles()
                val copyAgentContext = dialog.shouldCopyAgentContext()

                if (rawName.isNotBlank() && rawBranchName.isNotBlank()) {
                    val worktreeName = onValidateWorktreeName(rawName)
                    if (!worktreeName.isNullOrBlank()) {
                        val resolved = if (createNewBranch) {
                            onValidateBranchName(rawBranchName)?.let { ResolvedBranch(it, createNewBranch = true) }
                        } else {
                            resolveExistingOrRemoteBranch(project, repository, rawBranchName)
                        }
                        if (resolved != null) {
                            when {
                                copyIgnoredFiles -> onCreateWorktreeWithIgnoredFiles(
                                    worktreeName,
                                    resolved.name,
                                    resolved.createNewBranch
                                )
                                copyAgentContext -> onCreateWorktreeWithAgentContext(
                                    worktreeName,
                                    resolved.name,
                                    resolved.createNewBranch
                                )
                                else -> onCreateWorktree(
                                    worktreeName,
                                    resolved.name,
                                    resolved.createNewBranch
                                )
                            }
                        }
                    }
                }
            }
        },
        onDeleteWorktree = onDeleteWorktree,
        onConfirmDelete = onConfirmDelete,
        onMergeIntoBranch = onMergeIntoBranch,
        onPullFromRemote = onPullFromRemote,
        onPushToRemote = onPushToRemote,
        onOpenInTerminal = onOpenInTerminal,
        onRevealInExplorer = onRevealInExplorer,
        onCopyPath = onCopyPath,
        onCopyBranch = onCopyBranch,
        onCopyCommit = onCopyCommit
    )
}

private data class ResolvedBranch(val name: String, val createNewBranch: Boolean)

/**
 * Resolve an existing local branch, or let the user pick a remote branch when the local name is missing.
 * Remote selections return createNewBranch=true so GitWorktreeService can create a local tracking branch.
 */
private fun resolveExistingOrRemoteBranch(
    project: Project,
    repository: GitRepository,
    rawBranchName: String
): ResolvedBranch? {
    val candidate = sanitizeBranchName(rawBranchName)
    if (candidate.isBlank()) {
        Messages.showErrorDialog(
            project,
            "Branch name is invalid.",
            "Invalid Branch Name"
        )
        return null
    }

    if (repository.branches.localBranches.any { it.name == candidate }) {
        return ResolvedBranch(candidate, createNewBranch = false)
    }

    val remoteBranches = repository.branches.remoteBranches.map { it.name }.sorted()
    val matchingRemotes = remoteBranches.filter {
        it == candidate || it.endsWith("/$candidate") || it.substringAfter('/') == candidate
    }

    if (matchingRemotes.size == 1) {
        return ResolvedBranch(matchingRemotes.first(), createNewBranch = true)
    }

    if (remoteBranches.isNotEmpty()) {
        val dialog = RemoteBranchSelectionDialog(
            project,
            if (matchingRemotes.isNotEmpty()) matchingRemotes else remoteBranches
        )
        if (dialog.showAndGet()) {
            val selected = dialog.selectedBranch ?: return null
            return ResolvedBranch(selected, createNewBranch = true)
        }
        return null
    }

    Messages.showErrorDialog(
        project,
        "Local branch '$candidate' does not exist. Enable 'Create new branch' or enter an existing branch name.",
        "Branch Not Found"
    )
    return null
}

private fun listWorktreesInBackground(project: Project, repository: GitRepository): List<WorktreeInfo>? {
    val gitWorktreeService = GitWorktreeService.getInstance(project)

    return try {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable {
                gitWorktreeService.listWorktrees(repository).getOrNull()
            },
            "Checking existing worktrees…",
            true,
            project
        )
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
