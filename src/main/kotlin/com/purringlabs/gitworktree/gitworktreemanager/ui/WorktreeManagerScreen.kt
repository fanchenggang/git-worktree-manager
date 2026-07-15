package com.purringlabs.gitworktree.gitworktreemanager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.purringlabs.gitworktree.gitworktreemanager.registerGitRepoAutoRefresh
import com.purringlabs.gitworktree.gitworktreemanager.repository.WorktreeRepository
import com.purringlabs.gitworktree.gitworktreemanager.services.FileOperationsService
import com.purringlabs.gitworktree.gitworktreemanager.services.IgnoredFilesService
import com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Wrapper composable that holds the Project reference and manages the ViewModel / controller.
 * This is the only composable that knows about Project and IntelliJ Platform APIs.
 */
@Composable
internal fun WorktreeManagerContent(project: Project) {
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

    val controller = remember(project, viewModel, uiScope) {
        WorktreeToolWindowController(
            project = project,
            viewModel = viewModel,
            uiScope = uiScope,
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

    val actions = remember(controller) { controller.screenActions() }

    WorktreeListScreen(
        state = viewModel.state,
        currentProjectBasePath = project.basePath,
        onSearchQueryChange = actions.onSearchQueryChange,
        onRefresh = actions.onRefresh,
        onPrune = actions.onPrune,
        onOpenWorktree = actions.onOpenWorktree,
        onShowContextMenu = actions.onShowContextMenu,
        onCreateWorktreeRequest = actions.onCreateWorktreeRequest,
        onDeleteWorktree = actions.onDeleteWorktree,
        onConfirmDelete = actions.onConfirmDelete,
        onMergeIntoBranch = actions.onMergeIntoBranch,
        onPullFromRemote = actions.onPullFromRemote,
        onPushToRemote = actions.onPushToRemote,
        onOpenInTerminal = actions.onOpenInTerminal,
        onRevealInExplorer = actions.onRevealInExplorer,
        onCopyPath = actions.onCopyPath,
        onCopyBranch = actions.onCopyBranch,
        onCopyCommit = actions.onCopyCommit,
    )
}
