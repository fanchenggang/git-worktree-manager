package com.purringlabs.gitworktree.gitworktreemanager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.input.pointer.isSecondaryPressed
import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.ide.DataManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepository
import com.purringlabs.gitworktree.gitworktreemanager.services.ClaudeCodeContextService
import com.purringlabs.gitworktree.gitworktreemanager.services.FileOperationsService
import com.purringlabs.gitworktree.gitworktreemanager.services.GitWorktreeService
import com.purringlabs.gitworktree.gitworktreemanager.services.IgnoredFilesService
import com.purringlabs.gitworktree.gitworktreemanager.services.NoRepositoryUiHelper
import com.purringlabs.gitworktree.gitworktreemanager.services.UiErrorMapper
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.AgentContextCopyDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.AgentContextCopyResultDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.CopyResultDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.ErrorDetailsDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.IgnoredFilesSelectionDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.MergeIntoBranchDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.CreateWorktreeDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.RemoteBranchSelectionDialog
import com.purringlabs.gitworktree.gitworktreemanager.util.BranchNameSanitizer
import git4idea.repo.GitRepositoryManager
import com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeViewModel
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.awt.Cursor
import java.awt.Frame
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Paths
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JProgressBar

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
                val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(worktree.path)
                if (virtualFile != null) {
                    org.jetbrains.plugins.terminal.TerminalView.getInstance(project).openTerminalIn(virtualFile)
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
            com.intellij.ide.actions.RevealFileAction.openDirectory(File(worktree.path))
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

    WorktreeListContent(
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
                return@WorktreeListContent
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
                return@WorktreeListContent
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
                return@WorktreeListContent
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

@VisibleForTesting
internal fun registerGitRepoAutoRefresh(
    project: Project,
    requestAutoRefresh: () -> Unit,
    cancelAutoRefresh: () -> Unit
): Disposable {
    val connection = project.messageBus.connect()
    connection.subscribe(
        GitRepository.GIT_REPO_CHANGE,
        GitRepositoryChangeListener { requestAutoRefresh() }
    )
    return Disposable {
        cancelAutoRefresh()
        connection.dispose()
    }
}

@VisibleForTesting
internal fun canonicalizePath(path: String): String = FileUtil.toCanonicalPath(path)

private fun worktreeFolderName(path: String): String = File(path).name

@VisibleForTesting
internal fun sanitizeBranchName(input: String): String = BranchNameSanitizer.sanitize(input)

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

@VisibleForTesting
internal fun isWorktreeAlreadyOpen(openProjectBasePaths: Sequence<String?>, worktreePath: String): Boolean {
    val canonicalTarget = canonicalizePath(worktreePath)
    return openProjectBasePaths
        .filterNotNull()
        .any { canonicalizePath(it) == canonicalTarget }
}

@VisibleForTesting
internal fun restoreFromMinimizedPreservingMaximized(extendedState: Int): Int {
    return extendedState and Frame.ICONIFIED.inv()
}

@VisibleForTesting
internal fun isCurrentWorktree(currentProjectBasePath: String?, worktreePath: String): Boolean {
    val canonicalCurrent = currentProjectBasePath?.let { canonicalizePath(it) } ?: return false
    return canonicalizePath(worktreePath) == canonicalCurrent
}

@VisibleForTesting
internal fun isDeleteEnabled(isMain: Boolean, isCurrent: Boolean, isDeleting: Boolean): Boolean {
    // Never allow deleting the main worktree, or the currently-open worktree.
    return !isDeleting && !isMain && !isCurrent
}

@VisibleForTesting
internal fun sortWorktreesForDisplay(
    worktrees: List<WorktreeInfo>,
    currentProjectBasePath: String?
): List<WorktreeInfo> {
    return worktrees.sortedWith(
        compareByDescending<WorktreeInfo> { wt ->
            isCurrentWorktree(currentProjectBasePath = currentProjectBasePath, worktreePath = wt.path)
        }
            // Pin main directly under current.
            // Note: in practice, current+main should never both be true at once, but this makes ordering stable.
            .thenByDescending { wt -> wt.isMain }
            .thenBy { wt -> wt.branch ?: "" }
            .thenBy { wt -> wt.path }
    )
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

private fun openOrFocusWorktree(
    currentProject: Project,
    worktreePath: String
) {
    val alreadyOpenProject = ProjectManager.getInstance().openProjects.firstOrNull { p ->
        val base = p.basePath ?: return@firstOrNull false
        canonicalizePath(base) == canonicalizePath(worktreePath)
    }

    ApplicationManager.getApplication().invokeLater {
        runCatching {
            if (alreadyOpenProject != null) {
                // Prefer IDE focus APIs; fall back to raw frame-toFront.
                val ideFrame = WindowManager.getInstance().getIdeFrame(alreadyOpenProject)
                if (ideFrame != null) {
                    IdeFocusManager.getInstance(alreadyOpenProject).requestFocus(ideFrame.component, true)
                }

                val frame = WindowManager.getInstance().getFrame(alreadyOpenProject)
                if (frame != null) {
                    // Only restore from minimized; do not clear maximized state.
                    frame.extendedState = restoreFromMinimizedPreservingMaximized(frame.extendedState)
                    frame.toFront()
                    frame.requestFocus()
                }
            } else {
                ProjectUtil.openOrImport(File(worktreePath).toPath(), currentProject, true)
            }
        }
    }
}
/**
 * Pure UI composable for displaying the worktree list
 * No dependency on Project - can be previewed with mock data
 */
@Composable
private fun WorktreeListContent(
    state: com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeState,
    currentProjectBasePath: String?,
    onSearchQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenWorktree: (WorktreeInfo) -> Unit,
    onDeleteWorktree: (WorktreeInfo) -> Unit,
    onCreateWorktreeRequest: () -> Unit,
    onConfirmDelete: (WorktreeInfo) -> Boolean,
    onShowContextMenu: (WorktreeInfo, Offset) -> Unit,
    onMergeIntoBranch: (WorktreeInfo) -> Unit,
    onPrune: () -> Unit,
    onPullFromRemote: (WorktreeInfo) -> Unit,
    onPushToRemote: (WorktreeInfo) -> Unit,
    onOpenInTerminal: (WorktreeInfo) -> Unit,
    onRevealInExplorer: (WorktreeInfo) -> Unit,
    onCopyPath: (WorktreeInfo) -> Unit,
    onCopyBranch: (WorktreeInfo) -> Unit,
    onCopyCommit: (WorktreeInfo) -> Unit
) {
    val isBusy = state.isCreating ||
        state.isScanning ||
        state.isPruning ||
        state.deletingWorktreePath != null ||
        state.mergingSourceBranch != null ||
        state.pushingBranch != null ||
        state.pullingBranch != null
    val statusText = when {
        state.isScanning -> "Scanning ignored files..."
        state.isCreating -> "Creating worktree..."
        state.isPruning -> "Pruning worktrees..."
        state.deletingWorktreePath != null -> "Deleting worktree..."
        state.mergingSourceBranch != null && state.mergingTargetBranch != null -> "Merging ${state.mergingSourceBranch} into ${state.mergingTargetBranch}..."
        state.pushingBranch != null -> "Pushing ${state.pushingBranch}..."
        state.pullingBranch != null -> "Pulling ${state.pullingBranch}..."
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (isBusy) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SwingPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    factory = {
                        JProgressBar().apply {
                            isIndeterminate = true
                            border = null
                        }
                    }
                )
                statusText?.let { Text(it) }
            }
        }

        // Create button at the top
        OutlinedButton(onClick = {
            onCreateWorktreeRequest()
        }, enabled = !state.isCreating && !state.isScanning && state.pushingBranch == null) {
            Text("Create Worktree")
        }

        // Error message
        state.error?.let { error ->
            Text(
                text = error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        // Search
        val filteredWorktrees = remember(state.worktrees, state.searchQuery) {
            val q = state.searchQuery.trim().lowercase()
            if (q.isBlank()) return@remember state.worktrees

            state.worktrees.filter { wt ->
                val branch = wt.branch ?: ""
                listOf(branch, wt.path, wt.commit).any { it.lowercase().contains(q) }
            }
        }

        // Note: current-worktree detection is implemented via isCurrentWorktree(...)
        // and kept as a pure function to make it easy to unit-test.

        val searchBorder = if (isSystemInDarkTheme()) Color(0x33FFFFFF) else Color(0x22000000)
        val searchBg = if (isSystemInDarkTheme()) Color(0x14FFFFFF) else Color(0x0A000000)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BasicTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = if (isSystemInDarkTheme()) Color.White else Color.Black),
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, searchBorder, RoundedCornerShape(6.dp))
                    .background(searchBg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                decorationBox = { innerTextField ->
                    Box {
                        if (state.searchQuery.isBlank()) {
                            Text(
                                text = "Search worktrees…",
                                fontWeight = FontWeight.Light
                            )
                        }
                        innerTextField()
                    }
                }
            )

            OutlinedButton(
                onClick = { onSearchQueryChange("") },
                enabled = state.searchQuery.isNotBlank()
            ) {
                Text("Clear")
            }

            OutlinedButton(onClick = onRefresh, enabled = !isBusy) {
                Text("Refresh")
            }

            OutlinedButton(onClick = onPrune, enabled = !isBusy) {
                Text("Prune")
            }
        }

        // Worktree list
        // Sort so the currently-open worktree is always at the top, and the main worktree is pinned just under it.
        val sortedWorktrees = remember(filteredWorktrees, currentProjectBasePath) {
            sortWorktreesForDisplay(
                worktrees = filteredWorktrees,
                currentProjectBasePath = currentProjectBasePath
            )
        }

        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Loading worktrees...")
            }
        } else if (state.worktrees.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No worktrees found")
            }
        } else if (filteredWorktrees.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No matches")
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(sortedWorktrees) { worktree ->
                    val isCurrent = isCurrentWorktree(
                        currentProjectBasePath = currentProjectBasePath,
                        worktreePath = worktree.path
                    )

                    WorktreeItem(
                        worktree = worktree,
                        isCurrent = isCurrent,
                        isDeleting = state.deletingWorktreePath == worktree.path,
                        onOpen = { onOpenWorktree(worktree) },
                        onDelete = {
                            if (onConfirmDelete(worktree)) {
                                onDeleteWorktree(worktree)
                            }
                        },
                        onContextMenu = { offset -> onShowContextMenu(worktree, offset) },
                        onOpenInTerminal = { onOpenInTerminal(worktree) },
                        onRevealInExplorer = { onRevealInExplorer(worktree) }
                    )
                }
            }
        }
    }
}

/**
 * Pure UI composable for displaying a single worktree item
 * No dependency on Project - can be previewed with mock data
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun WorktreeItem(
    worktree: WorktreeInfo,
    isCurrent: Boolean,
    isDeleting: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onContextMenu: (Offset) -> Unit,
    onOpenInTerminal: () -> Unit,
    onRevealInExplorer: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }

    val currentBackground = when {
        !isCurrent -> Color.Transparent
        isSystemInDarkTheme() -> Color(0x162F80FF) // subtle blue tint
        else -> Color(0x142F80FF)
    }

    val hoverBackground = when {
        !isHovered -> Color.Transparent
        isSystemInDarkTheme() -> Color(0x22FFFFFF)
        else -> Color(0x14000000)
    }

    // If it's both current + hovered, blend by just preferring hover.
    val rowBackground = if (isHovered) hoverBackground else currentBackground
    val secondaryColor = if (isSystemInDarkTheme()) Color(0xFFB0B0B0) else Color(0xFF5C5C5C)
    val branchLine = buildString {
        append(worktree.branch ?: "detached HEAD")
        append(" · ")
        append(worktree.commit.take(8))
    }
    val pathDisplay = worktree.path.replace('\\', '/')

    val rightClickModifier = Modifier.pointerInput(worktree) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    val pos = event.changes.firstOrNull()?.position ?: Offset.Zero
                    onContextMenu(pos)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = rightClickModifier
                .then(
                    Modifier
                        .fillMaxWidth()
                        .pointerMoveFilter(
                            onEnter = {
                                isHovered = true
                                false
                            },
                            onExit = {
                                isHovered = false
                                false
                            }
                        )
                        .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { onOpen() }
                            )
                        }
                        .background(rowBackground)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.weight(0.26f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier.width(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrent) {
                        Text(text = "✓", fontWeight = FontWeight.Medium)
                    }
                }
                Text(
                    text = worktreeFolderName(worktree.path),
                    fontWeight = if (worktree.isMain) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }

            Text(
                text = branchLine,
                color = secondaryColor,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.30f).fillMaxWidth()
            )

            Text(
                text = pathDisplay,
                color = secondaryColor,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.36f).fillMaxWidth()
            )

            val deleteEnabled = isDeleteEnabled(isMain = worktree.isMain, isCurrent = isCurrent, isDeleting = isDeleting)
            var isDeleteHovered by remember { mutableStateOf(false) }

            if (isHovered) {
                fun Modifier.actionCursor(enabled: Boolean): Modifier {
                    return pointerHoverIcon(
                        if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default
                    )
                }

                @Composable
                fun IdeaIcon(icon: Icon) {
                    SwingPanel(
                        modifier = Modifier.size(16.dp),
                        factory = {
                            JLabel().apply {
                                isOpaque = false
                                this.icon = icon
                            }
                        }
                    )
                }

                @Composable
                fun IconActionButton(
                    icon: Icon,
                    tooltip: String,
                    enabled: Boolean = true,
                    onClick: () -> Unit
                ) {
                    var hovered by remember { mutableStateOf(false) }
                    val bg = if (hovered) {
                        if (isSystemInDarkTheme()) Color(0x22FFFFFF) else Color(0x14000000)
                    } else Color.Transparent

                    Box(
                        modifier = Modifier
                            .actionCursor(enabled)
                            .pointerMoveFilter(
                                onEnter = {
                                    hovered = true
                                    false
                                },
                                onExit = {
                                    hovered = false
                                    false
                                }
                            )
                            .pointerInput(enabled) {
                                if (enabled) detectTapGestures(onTap = { onClick() })
                            }
                            .size(28.dp)
                            .background(bg, RoundedCornerShape(6.dp))
                            .padding(6.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            IdeaIcon(icon = icon)
                        }

                        if (hovered) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = (-40).dp)
                                    .background(
                                        if (isSystemInDarkTheme()) Color(0xEE2B2B2B) else Color(0xEEFFFFFF),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSystemInDarkTheme()) Color(0x55FFFFFF) else Color(0x22000000),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(text = tooltip, fontWeight = FontWeight.Light)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconActionButton(
                        icon = AllIcons.Nodes.Console,
                        tooltip = "Open in Terminal",
                        onClick = onOpenInTerminal
                    )
                    IconActionButton(
                        icon = AllIcons.Nodes.Folder,
                        tooltip = "Reveal in Explorer",
                        onClick = onRevealInExplorer
                    )

                    Box(
                        modifier = Modifier.pointerMoveFilter(
                            onEnter = {
                                isDeleteHovered = true
                                false
                            },
                            onExit = {
                                isDeleteHovered = false
                                false
                            }
                        )
                    ) {
                        IconActionButton(
                            icon = AllIcons.General.Remove,
                            tooltip = "Delete worktree",
                            enabled = deleteEnabled,
                            onClick = onDelete
                        )

                        if (!deleteEnabled && isDeleteHovered) {
                            val tooltipText = when {
                                worktree.isMain -> "Main working tree can’t be removed."
                                isCurrent -> "This worktree is currently open. Close it (or delete from another window) to remove it."
                                else -> null
                            }

                            if (tooltipText != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(y = (-40).dp)
                                        .background(
                                            if (isSystemInDarkTheme()) Color(0xEE2B2B2B) else Color(0xEEFFFFFF),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .border(1.dp, if (isSystemInDarkTheme()) Color(0x55FFFFFF) else Color(0x22000000), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(text = tooltipText, fontWeight = FontWeight.Light)
                                }
                            }
                        }
                    }
                }
            }
        }

    }
}
