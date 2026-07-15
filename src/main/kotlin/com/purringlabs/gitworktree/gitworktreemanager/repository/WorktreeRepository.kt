package com.purringlabs.gitworktree.gitworktreemanager.repository

import com.intellij.openapi.project.Project
import com.purringlabs.gitworktree.gitworktreemanager.exceptions.NoRepositoryException
import com.purringlabs.gitworktree.gitworktreemanager.models.CreateWorktreeResult
import com.purringlabs.gitworktree.gitworktreemanager.models.DeleteWorktreeResult
import com.purringlabs.gitworktree.gitworktreemanager.models.WorktreeInfo
import com.purringlabs.gitworktree.gitworktreemanager.services.GitWorktreeService
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository layer for worktree operations
 * Handles data access and wraps GitWorktreeService
 */
class WorktreeRepository(private val project: Project) : WorktreeRepositoryContract {

    private val service: GitWorktreeService
        get() = GitWorktreeService.getInstance(project)

    private val currentRepository: GitRepository?
        get() = GitRepositoryManager.getInstance(project)
            .repositories
            .firstOrNull { repo -> File(repo.root.path).exists() }

    /**
     * Fetches the list of worktrees
     * @return Result containing list of WorktreeInfo or error
     */
    override suspend fun fetchWorktrees(): Result<List<WorktreeInfo>> = withContext(Dispatchers.IO) {
        val repository = currentRepository
        if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.listWorktrees(repository)
        }
    }

    /**
     * Creates a new worktree
     * @param name Name for the worktree directory
     * @param branchName Name of the branch to create/checkout
     * @return Result indicating success or failure
     */
    override suspend fun createWorktree(
        name: String,
        branchName: String,
        createNewBranch: Boolean
    ): Result<CreateWorktreeResult> = withContext(Dispatchers.IO) {
        val repository = currentRepository
        if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.createWorktree(repository, name, branchName, createNewBranch)
        }
    }

    /**
     * Deletes a worktree
     * @param worktreePath Path to the worktree to delete
     * @return Result indicating success or failure
     */
    override suspend fun deleteWorktree(worktreePath: String, branchName: String?): Result<DeleteWorktreeResult> = withContext(Dispatchers.IO) {
        val repository = currentRepository
        if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.deleteWorktree(repository, worktreePath, branchName)
        }
    }

    override suspend fun mergeBranchInto(sourceBranch: String, targetWorktreePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        val repository = currentRepository
        if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.mergeBranchInto(repository, sourceBranch, targetWorktreePath)
        }
    }

    override suspend fun pullBranch(worktreePath: String, branchName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val repository = currentRepository
        if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.pullBranch(repository, worktreePath, branchName)
        }
    }

    override suspend fun pushBranch(worktreePath: String, branchName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val repository = currentRepository
        if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.pushBranch(repository, worktreePath, branchName)
        }
    }

    override suspend fun pruneWorktrees(): Result<Unit> = withContext(Dispatchers.IO) {
        val repository = currentRepository
        if (repository == null) {
            Result.failure(NoRepositoryException("No Git repository found in project"))
        } else {
            service.pruneWorktrees(repository)
        }
    }
}
