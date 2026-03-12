package com.purringlabs.gitworktree.gitworktreemanager.repository

import com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult
import com.purringlabs.gitworktree.gitworktreemanager.models.DeleteWorktreeResult
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo

interface WorktreeRepositoryContract {
    suspend fun fetchWorktrees(): Result<List<WorktreeInfo>>

    suspend fun createWorktree(
        name: String,
        branchName: String,
        createNewBranch: Boolean = true
    ): Result<CreateWorktreeResult>

    suspend fun deleteWorktree(worktreePath: String, branchName: String?): Result<DeleteWorktreeResult>

    suspend fun mergeBranchInto(sourceBranch: String, targetWorktreePath: String): Result<Unit>

    suspend fun pullBranch(worktreePath: String, branchName: String): Result<Unit>

    suspend fun pushBranch(worktreePath: String, branchName: String): Result<Unit>
}
