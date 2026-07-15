package com.purringlabs.gitworktree.gitworktreemanager.ui

import androidx.compose.ui.geometry.Offset
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.PopupUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.awt.RelativePoint
import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle
import com.purringlabs.gitworktree.gitworktreemanager.isCurrentWorktree
import com.purringlabs.gitworktree.gitworktreemanager.isDeleteEnabled
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.openOrFocusWorktree
import com.purringlabs.gitworktree.gitworktreemanager.services.NoRepositoryUiHelper
import com.purringlabs.gitworktree.gitworktreemanager.services.UiErrorMapper
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.ErrorDetailsDialog
import com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs.MergeIntoBranchDialog
import com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeViewModel
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.Icon

/**
 * Compact callback surface for Compose screens.
 */
internal data class WorktreeScreenActions(
    val onSearchQueryChange: (String) -> Unit,
    val onRefresh: () -> Unit,
    val onPrune: () -> Unit,
    val onOpenWorktree: (WorktreeInfo) -> Unit,
    val onShowContextMenu: (WorktreeInfo, Offset) -> Unit,
    val onCreateWorktreeRequest: () -> Unit,
    val onDeleteWorktree: (WorktreeInfo) -> Unit,
    val onConfirmDelete: (WorktreeInfo) -> Boolean,
    val onMergeIntoBranch: (WorktreeInfo) -> Unit,
    val onPullFromRemote: (WorktreeInfo) -> Unit,
    val onPushToRemote: (WorktreeInfo) -> Unit,
    val onOpenInTerminal: (WorktreeInfo) -> Unit,
    val onRevealInExplorer: (WorktreeInfo) -> Unit,
    val onCopyPath: (WorktreeInfo) -> Unit,
    val onCopyBranch: (WorktreeInfo) -> Unit,
    val onCopyCommit: (WorktreeInfo) -> Unit,
)

/**
 * Owns tool-window action wiring: create/delete/merge/push/pull/prune, context menu, clipboard, terminal.
 */
internal class WorktreeToolWindowController(
    private val project: Project,
    private val viewModel: WorktreeViewModel,
    private val uiScope: CoroutineScope,
) {
    private val currentProjectBasePath: String? = project.basePath

    private val createFlow = WorktreeCreateFlow(
        project = project,
        viewModel = viewModel,
        uiScope = uiScope,
        findValidRepository = ::findValidRepository,
        showOperationError = ::showOperationError,
    )

    fun screenActions(): WorktreeScreenActions = WorktreeScreenActions(
        onSearchQueryChange = viewModel::setSearchQuery,
        onRefresh = ::refresh,
        onPrune = ::prune,
        onOpenWorktree = ::openWorktree,
        onShowContextMenu = ::showContextMenu,
        onCreateWorktreeRequest = createFlow::requestCreate,
        onDeleteWorktree = ::delete,
        onConfirmDelete = ::confirmDelete,
        onMergeIntoBranch = ::mergeIntoBranch,
        onPullFromRemote = ::pullFromRemote,
        onPushToRemote = ::pushToRemote,
        onOpenInTerminal = ::openInTerminal,
        onRevealInExplorer = ::revealInExplorer,
        onCopyPath = ::copyPath,
        onCopyBranch = ::copyBranch,
        onCopyCommit = ::copyCommit,
    )

    fun refresh() {
        if (findValidRepository() == null) {
            NoRepositoryUiHelper.showNoRepositoryDialog(
                project = project,
                attemptedOperation = "LIST_WORKTREES"
            )
            return
        }
        viewModel.refreshWorktrees()
    }

    fun prune() {
        if (findValidRepository() == null) {
            NoRepositoryUiHelper.showNoRepositoryDialog(
                project = project,
                attemptedOperation = "PRUNE_WORKTREES"
            )
            return
        }
        viewModel.pruneWorktrees(
            onSuccess = {
                ApplicationManager.getApplication().invokeLater {
                    PopupUtil.showBalloonForActiveComponent(
                        MyMessageBundle.message("balloon.pruned"),
                        MessageType.INFO
                    )
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(error, "PRUNE_WORKTREES")
                }
            }
        )
    }

    fun openWorktree(worktree: WorktreeInfo) {
        openOrFocusWorktree(project, worktree.path)
    }

    fun confirmDelete(worktree: WorktreeInfo): Boolean {
        val result = Messages.showYesNoDialog(
            project,
            MyMessageBundle.message("dialog.delete.confirm", worktree.path),
            MyMessageBundle.message("dialog.delete.title"),
            Messages.getWarningIcon()
        )
        return result == Messages.YES
    }

    fun delete(worktree: WorktreeInfo) {
        viewModel.deleteWorktree(
            worktreePath = worktree.path,
            onSuccess = { result ->
                ApplicationManager.getApplication().invokeLater {
                    if (result.branchDeleted) {
                        PopupUtil.showBalloonForActiveComponent(
                            MyMessageBundle.message("balloon.worktreeDeleted"),
                            MessageType.INFO
                        )
                    } else {
                        val reason = result.branchDeleteError?.gitErrorOutput
                            ?: result.branchDeleteError?.errorMessage
                            ?: MyMessageBundle.message("error.unknownReason")
                        Messages.showWarningDialog(
                            project,
                            MyMessageBundle.message("dialog.partialSuccess.branchCleanupFailed", reason),
                            MyMessageBundle.message("dialog.partialSuccess.title")
                        )
                    }
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(error, "DELETE_WORKTREE")
                }
            }
        )
    }

    fun mergeIntoBranch(sourceWorktree: WorktreeInfo) {
        val sourceBranch = sourceWorktree.branch
        if (sourceBranch.isNullOrBlank()) return
        val targetWorktrees = viewModel.state.worktrees
            .filter { it.path != sourceWorktree.path && it.branch != null }
            .sortedByDescending { it.branch?.lowercase() == "develop" }
        if (targetWorktrees.isEmpty()) {
            Messages.showInfoMessage(
                project,
                MyMessageBundle.message("dialog.merge.noTarget"),
                MyMessageBundle.message("dialog.merge.title")
            )
            return
        }
        val dialog = MergeIntoBranchDialog(project, sourceBranch, targetWorktrees)
        if (!dialog.showAndGet()) return
        val target = dialog.getSelectedTarget() ?: return
        val targetBranch = target.branch!!
        viewModel.mergeBranchInto(
            sourceBranch = sourceBranch,
            targetWorktreePath = target.path,
            targetBranch = targetBranch,
            onSuccess = {
                ApplicationManager.getApplication().invokeLater {
                    val choice = Messages.showDialog(
                        project,
                        MyMessageBundle.message("dialog.mergeSuccess.message", sourceBranch, targetBranch),
                        MyMessageBundle.message("dialog.mergeSuccess.title"),
                        arrayOf(
                            MyMessageBundle.message("action.cancel"),
                            MyMessageBundle.message("action.push")
                        ),
                        1,
                        Messages.getQuestionIcon()
                    )
                    if (choice == 1) {
                        viewModel.pushBranch(
                            worktreePath = target.path,
                            branchName = targetBranch,
                            onSuccess = {
                                ApplicationManager.getApplication().invokeLater {
                                    PopupUtil.showBalloonForActiveComponent(
                                        MyMessageBundle.message("balloon.pushed", targetBranch),
                                        MessageType.INFO
                                    )
                                }
                            },
                            onError = { error ->
                                ApplicationManager.getApplication().invokeLater {
                                    showOperationError(error, "PUSH_BRANCH")
                                }
                            }
                        )
                    }
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(error, "MERGE_BRANCH")
                }
            }
        )
    }

    fun pullFromRemote(worktree: WorktreeInfo) {
        val branch = worktree.branch ?: return
        viewModel.pullBranch(
            worktreePath = worktree.path,
            branchName = branch,
            onSuccess = {
                ApplicationManager.getApplication().invokeLater {
                    PopupUtil.showBalloonForActiveComponent(
                        MyMessageBundle.message("balloon.pulled", branch),
                        MessageType.INFO
                    )
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(error, "PULL_BRANCH")
                }
            }
        )
    }

    fun pushToRemote(worktree: WorktreeInfo) {
        val branch = worktree.branch ?: return
        viewModel.pushToRemote(
            worktreePath = worktree.path,
            branchName = branch,
            onSuccess = {
                ApplicationManager.getApplication().invokeLater {
                    PopupUtil.showBalloonForActiveComponent(
                        MyMessageBundle.message("balloon.pushed", branch),
                        MessageType.INFO
                    )
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    showOperationError(error, "PUSH_BRANCH")
                }
            }
        )
    }

    fun openInTerminal(worktree: WorktreeInfo) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(worktree.path)
                if (virtualFile != null) {
                    TerminalToolWindowManager.getInstance(project).openTerminalIn(virtualFile)
                } else {
                    Messages.showErrorDialog(
                        project,
                        MyMessageBundle.message("error.worktreeDirMissing"),
                        MyMessageBundle.message("action.openInTerminal")
                    )
                }
            } catch (_: NoClassDefFoundError) {
                Messages.showErrorDialog(
                    project,
                    MyMessageBundle.message("error.terminalUnavailable"),
                    MyMessageBundle.message("action.openInTerminal")
                )
            } catch (e: Exception) {
                Messages.showErrorDialog(
                    project,
                    MyMessageBundle.message("error.openTerminalFailed", e.message ?: ""),
                    MyMessageBundle.message("action.openInTerminal")
                )
            }
        }
    }

    fun revealInExplorer(worktree: WorktreeInfo) {
        ApplicationManager.getApplication().invokeLater {
            RevealFileAction.openDirectory(File(worktree.path))
        }
    }

    fun copyPath(worktree: WorktreeInfo) {
        copyToClipboard(label = MyMessageBundle.message("label.path"), value = worktree.path)
    }

    fun copyBranch(worktree: WorktreeInfo) {
        val branch = worktree.branch
        if (branch != null) {
            copyToClipboard(label = MyMessageBundle.message("label.branch"), value = branch)
        } else {
            PopupUtil.showBalloonForActiveComponent(
                MyMessageBundle.message("balloon.noBranchToCopy"),
                MessageType.INFO
            )
        }
    }

    fun copyCommit(worktree: WorktreeInfo) {
        copyToClipboard(label = MyMessageBundle.message("label.commit"), value = worktree.commit)
    }

    fun showContextMenu(worktree: WorktreeInfo, offset: Offset) {
        val component = IdeFocusManager.getInstance(project).focusOwner ?: return
        val dataContext: DataContext = DataManager.getInstance().getDataContext(component)

        val st = viewModel.state
        val branch = worktree.branch
        val isCurrent = isCurrentWorktree(currentProjectBasePath = currentProjectBasePath, worktreePath = worktree.path)
        val deleteEnabled = isDeleteEnabled(
            isMain = worktree.isMain,
            isCurrent = isCurrent,
            isDeleting = st.deletingWorktreePath == worktree.path
        )
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
            add(
                makeAction(
                    MyMessageBundle.message("action.mergeIntoBranch"),
                    AllIcons.Vcs.Merge,
                    visible = branch != null,
                    enabled = !busy && branch != null
                ) { mergeIntoBranch(worktree) }
            )
            add(
                makeAction(
                    MyMessageBundle.message("action.pullFromRemote"),
                    AllIcons.Actions.Refresh,
                    visible = branch != null,
                    enabled = !busy && branch != null
                ) { pullFromRemote(worktree) }
            )
            add(
                makeAction(
                    MyMessageBundle.message("action.pushToRemote"),
                    AllIcons.Vcs.Push,
                    visible = branch != null,
                    enabled = !busy && branch != null
                ) { pushToRemote(worktree) }
            )
            addSeparator()
            add(
                makeAction(
                    MyMessageBundle.message("action.openInTerminal"),
                    AllIcons.Nodes.Console,
                    enabled = !busy
                ) { openInTerminal(worktree) }
            )
            add(
                makeAction(
                    MyMessageBundle.message("action.revealInExplorer"),
                    AllIcons.Nodes.Folder,
                    enabled = !busy
                ) { revealInExplorer(worktree) }
            )
            addSeparator()
            add(
                makeAction(MyMessageBundle.message("action.copyPath"), AllIcons.Actions.Copy, enabled = true) {
                    copyPath(worktree)
                }
            )
            add(
                makeAction(
                    MyMessageBundle.message("action.copyBranch"),
                    AllIcons.Actions.Copy,
                    visible = branch != null,
                    enabled = branch != null
                ) { copyBranch(worktree) }
            )
            add(
                makeAction(MyMessageBundle.message("action.copyCommit"), AllIcons.Actions.Copy, enabled = true) {
                    copyCommit(worktree)
                }
            )
            addSeparator()
            add(
                makeAction(
                    MyMessageBundle.message("action.deleteWorktree"),
                    AllIcons.General.Remove,
                    enabled = !busy && deleteEnabled
                ) {
                    if (confirmDelete(worktree)) {
                        delete(worktree)
                    }
                }
            )
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

    private fun copyToClipboard(label: String, value: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(value))
        PopupUtil.showBalloonForActiveComponent(
            MyMessageBundle.message("balloon.copied", label),
            MessageType.INFO
        )
    }

    private fun showOperationError(error: Throwable, operation: String) {
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

    private fun findValidRepository(): GitRepository? {
        return GitRepositoryManager.getInstance(project)
            .repositories
            .firstOrNull { repo -> File(repo.root.path).exists() }
    }
}
