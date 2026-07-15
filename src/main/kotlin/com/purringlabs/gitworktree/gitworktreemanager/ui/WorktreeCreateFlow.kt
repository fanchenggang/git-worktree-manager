package com.purringlabs.gitworktree.gitworktreemanager.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ide.impl.ProjectUtil
import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.openOrFocusWorktree
import com.purringlabs.gitworktree.gitworktreemanager.sanitizeBranchName
import com.purringlabs.gitworktree.gitworktreemanager.services.ClaudeCodeContextService
import com.purringlabs.gitworktree.gitworktreemanager.services.GitWorktreeService
import com.purringlabs.gitworktree.gitworktreemanager.services.NoRepositoryUiHelper
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.AgentContextCopyDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.AgentContextCopyResultDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.CopyResultDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.CreateWorktreeDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.IgnoredFilesSelectionDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.RemoteBranchSelectionDialog
import com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeViewModel
import git4idea.repo.GitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Paths

internal data class ResolvedBranch(val name: String, val createNewBranch: Boolean)

/**
 * Create-worktree dialog, validation, and create variants (plain / ignored files / agent context).
 */
internal class WorktreeCreateFlow(
    private val project: Project,
    private val viewModel: WorktreeViewModel,
    private val uiScope: CoroutineScope,
    private val findValidRepository: () -> GitRepository?,
    private val showOperationError: (Throwable, String) -> Unit,
) {
    fun requestCreate() {
        val repository = findValidRepository()
        if (repository == null) {
            NoRepositoryUiHelper.showNoRepositoryDialog(
                project = project,
                attemptedOperation = "CREATE_WORKTREE"
            )
            return
        }

        val dialog = CreateWorktreeDialog(project)
        if (!dialog.showAndGet()) return

        val rawName = dialog.getWorktreeName()
        val rawBranchName = dialog.getBranchName()
        val createNewBranch = dialog.shouldCreateNewBranch()
        val copyIgnoredFiles = dialog.shouldCopyIgnoredFiles()
        val copyAgentContext = dialog.shouldCopyAgentContext()

        if (rawName.isBlank() || rawBranchName.isBlank()) return

        val worktreeName = validateWorktreeName(rawName) ?: return
        if (worktreeName.isBlank()) return

        val resolved = if (createNewBranch) {
            validateBranchName(rawBranchName)?.let { ResolvedBranch(it, createNewBranch = true) }
        } else {
            resolveExistingOrRemoteBranch(repository, rawBranchName)
        } ?: return

        when {
            copyIgnoredFiles -> createWithIgnoredFiles(worktreeName, resolved.name, resolved.createNewBranch)
            copyAgentContext -> createWithAgentContext(worktreeName, resolved.name, resolved.createNewBranch)
            else -> createWorktree(worktreeName, resolved.name, resolved.createNewBranch)
        }
    }

    fun createWorktree(name: String, branch: String, createNewBranch: Boolean) {
        viewModel.createWorktree(
            name = name,
            branchName = branch,
            createNewBranch = createNewBranch,
            onSuccess = { createResult ->
                ApplicationManager.getApplication().invokeLater {
                    ProjectUtil.openOrImport(File(createResult.path).toPath(), project, true)
                    Messages.showInfoMessage(
                        project,
                        if (createResult.created) {
                            MyMessageBundle.message("balloon.worktreeCreated")
                        } else {
                            MyMessageBundle.message("balloon.worktreeAlreadyExists")
                        },
                        MyMessageBundle.message("dialog.success.title")
                    )
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(error, "CREATE_WORKTREE")
                }
            }
        )
    }

    fun createWithIgnoredFiles(name: String, branch: String, createNewBranch: Boolean) {
        uiScope.launch {
            viewModel.scanIgnoredFiles()

            if (viewModel.state.scanError != null) {
                withContext(Dispatchers.Main) {
                    Messages.showErrorDialog(
                        project,
                        MyMessageBundle.message("error.scanIgnoredFiles", viewModel.state.scanError),
                        MyMessageBundle.message("dialog.error.title")
                    )
                }
                return@launch
            }

            val ignoredFiles = viewModel.state.ignoredFiles
            if (ignoredFiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Messages.showInfoMessage(
                        project,
                        MyMessageBundle.message("dialog.noIgnoredFiles.message"),
                        MyMessageBundle.message("dialog.noIgnoredFiles.title")
                    )
                }
                createWorktree(name, branch, createNewBranch)
                return@launch
            }

            val selectedFiles = withContext(Dispatchers.Main) {
                val dialog = IgnoredFilesSelectionDialog(project, ignoredFiles)
                if (dialog.showAndGet()) dialog.getSelectedFiles() else null
            }

            if (selectedFiles != null) {
                viewModel.createWorktreeWithIgnoredFiles(
                    worktreeName = name,
                    branchName = branch,
                    createNewBranch = createNewBranch,
                    selectedFiles = selectedFiles,
                    onSuccess = { createResult, copyResult ->
                        ApplicationManager.getApplication().invokeLater {
                            ProjectUtil.openOrImport(File(createResult.path).toPath(), project, true)
                            if (copyResult != null && (copyResult.successCount > 0 || copyResult.failed.isNotEmpty())) {
                                CopyResultDialog(project, copyResult).show()
                            }
                            Messages.showInfoMessage(
                                project,
                                if (createResult.created) {
                                    MyMessageBundle.message("balloon.worktreeCreated")
                                } else {
                                    MyMessageBundle.message("balloon.worktreeAlreadyExists")
                                },
                                MyMessageBundle.message("dialog.success.title")
                            )
                        }
                    },
                    onError = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            showOperationError(error, "CREATE_WORKTREE")
                        }
                    }
                )
            } else {
                createWorktree(name, branch, createNewBranch)
            }
        }
    }

    fun createWithAgentContext(name: String, branch: String, createNewBranch: Boolean) {
        uiScope.launch {
            val repository = findValidRepository()
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
                        MyMessageBundle.message("dialog.noAgentContext.message"),
                        MyMessageBundle.message("dialog.noAgentContext.title")
                    )
                }
                createWorktree(name, branch, createNewBranch)
                return@launch
            }

            val selectedOptions = withContext(Dispatchers.Main) {
                val dialog = AgentContextCopyDialog(project, options)
                if (dialog.showAndGet()) dialog.selectedOptions() else null
            }

            if (selectedOptions == null) {
                createWorktree(name, branch, createNewBranch)
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
                        if (copyResult != null &&
                            (copyResult.copiedCount > 0 || copyResult.failureCount > 0 || copyResult.skippedCount > 0)
                        ) {
                            AgentContextCopyResultDialog(project, copyResult).show()
                        }
                        Messages.showInfoMessage(
                            project,
                            if (createResult.created) {
                                MyMessageBundle.message("balloon.worktreeCreated")
                            } else {
                                MyMessageBundle.message("balloon.worktreeAlreadyExists")
                            },
                            MyMessageBundle.message("dialog.success.title")
                        )
                    }
                },
                onError = { error ->
                    ApplicationManager.getApplication().invokeLater {
                        showOperationError(error, "CREATE_WORKTREE")
                    }
                }
            )
        }
    }

    fun validateWorktreeName(initialName: String): String? {
        val repository = findValidRepository() ?: return initialName
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
                        MyMessageBundle.message("dialog.folderExists.message", path),
                        MyMessageBundle.message("dialog.folderExists.title"),
                        arrayOf(
                            MyMessageBundle.message("action.openExisting"),
                            MyMessageBundle.message("action.useAnotherName"),
                            MyMessageBundle.message("action.cancel")
                        ),
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
                                    MyMessageBundle.message("dialog.cannotOpenFolder.message", path),
                                    MyMessageBundle.message("dialog.cannotOpenFolder.title")
                                )
                            }
                            keepValidating = false
                        }
                        1 -> {
                            val newName = Messages.showInputDialog(
                                project,
                                MyMessageBundle.message("dialog.chooseAnotherName.message"),
                                MyMessageBundle.message("dialog.chooseAnotherName.title"),
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
        return result
    }

    fun validateBranchName(initialBranch: String): String? {
        val repository = findValidRepository() ?: return initialBranch
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
                    MyMessageBundle.message("dialog.invalidBranch.empty"),
                    MyMessageBundle.message("dialog.invalidBranch.title"),
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
                    MyMessageBundle.message("dialog.branchExists.message", branchName),
                    MyMessageBundle.message("dialog.branchExists.title"),
                    arrayOf(
                        MyMessageBundle.message("action.useAnotherName"),
                        MyMessageBundle.message("action.cancel")
                    ),
                    0,
                    Messages.getWarningIcon()
                )
                when (choice) {
                    0 -> {
                        val newBranch = Messages.showInputDialog(
                            project,
                            MyMessageBundle.message("dialog.chooseAnotherBranch.message"),
                            MyMessageBundle.message("dialog.chooseAnotherBranch.title"),
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
        return result
    }

    /**
     * Resolve an existing local branch, or let the user pick a remote branch when the local name is missing.
     * Remote selections return createNewBranch=true so GitWorktreeService can create a local tracking branch.
     */
    fun resolveExistingOrRemoteBranch(
        repository: GitRepository,
        rawBranchName: String
    ): ResolvedBranch? {
        val candidate = sanitizeBranchName(rawBranchName)
        if (candidate.isBlank()) {
            Messages.showErrorDialog(
                project,
                MyMessageBundle.message("dialog.invalidBranch.invalid"),
                MyMessageBundle.message("dialog.invalidBranch.title")
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
            MyMessageBundle.message("dialog.branchNotFound.message", candidate),
            MyMessageBundle.message("dialog.branchNotFound.title")
        )
        return null
    }
}

internal fun listWorktreesInBackground(project: Project, repository: GitRepository): List<WorktreeInfo>? {
    val gitWorktreeService = GitWorktreeService.getInstance(project)

    return try {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable {
                gitWorktreeService.listWorktrees(repository).getOrNull()
            },
            MyMessageBundle.message("status.checkingExistingWorktrees"),
            true,
            project
        )
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
