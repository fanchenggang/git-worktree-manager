package com.purringlabs.gitworktree.gitworktreemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle
import com.purringlabs.gitworktree.gitworktreemanager.isCurrentWorktree
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.sortWorktreesForDisplay
import com.purringlabs.gitworktree.gitworktreemanager.viewmodel.WorktreeState
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JProgressBar

/**
 * Pure UI composable for displaying the worktree list.
 * No dependency on Project — can be previewed with mock data.
 *
 * Context-menu actions (merge/pull/push/copy) are owned by the tool-window wiring and
 * passed through for a stable callback surface; they are not invoked from this screen.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun WorktreeListScreen(
    state: WorktreeState,
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
        state.isScanning -> MyMessageBundle.message("status.scanning")
        state.isCreating -> MyMessageBundle.message("status.creating")
        state.isPruning -> MyMessageBundle.message("status.pruning")
        state.deletingWorktreePath != null -> MyMessageBundle.message("status.deleting")
        state.mergingSourceBranch != null && state.mergingTargetBranch != null ->
            MyMessageBundle.message("status.merging", state.mergingSourceBranch, state.mergingTargetBranch)
        state.pushingBranch != null -> MyMessageBundle.message("status.pushing", state.pushingBranch)
        state.pullingBranch != null -> MyMessageBundle.message("status.pulling", state.pullingBranch)
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
            Text(MyMessageBundle.message("action.createWorktree"))
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
                                text = MyMessageBundle.message("search.placeholder"),
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
                Text(MyMessageBundle.message("action.clear"))
            }

            OutlinedButton(onClick = onRefresh, enabled = !isBusy) {
                Text(MyMessageBundle.message("action.refresh"))
            }

            OutlinedButton(onClick = onPrune, enabled = !isBusy) {
                Text(MyMessageBundle.message("action.prune"))
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
                Text(MyMessageBundle.message("status.loading"))
            }
        } else if (state.worktrees.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(MyMessageBundle.message("status.noWorktrees"))
            }
        } else if (filteredWorktrees.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(MyMessageBundle.message("status.noMatches"))
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
