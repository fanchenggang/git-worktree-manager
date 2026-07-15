package com.purringlabs.gitworktree.gitworktreemanager

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.util.BranchNameSanitizer
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import org.jetbrains.annotations.VisibleForTesting
import java.awt.Frame
import java.io.File

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

internal fun worktreeFolderName(path: String): String = File(path).name

@VisibleForTesting
internal fun sanitizeBranchName(input: String): String = BranchNameSanitizer.sanitize(input)

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
