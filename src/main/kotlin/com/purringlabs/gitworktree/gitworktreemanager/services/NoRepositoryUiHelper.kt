package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

object NoRepositoryUiHelper {

    fun showNoRepositoryDialog(
        project: Project,
        @Suppress("UNUSED_PARAMETER") attemptedOperation: String
    ) {
        val choice = Messages.showDialog(
            project,
            "No Git repository detected in this project.\n\n" +
                "Fixes:\n" +
                "• Open the repository root folder (the one containing .git)\n" +
                "• Or enable Git: VCS → Enable Version Control Integration…\n",
            "No Git Repository",
            arrayOf("Open Git", "Refresh", "How to fix"),
            0,
            Messages.getWarningIcon()
        )

        when (choice) {
            0 -> {
                val twm = ToolWindowManager.getInstance(project)
                val toolWindow = twm.getToolWindow("Git") ?: twm.getToolWindow("Version Control")
                toolWindow?.activate(null)
            }

            1 -> {
                // Best-effort: repository refresh. The UI will re-attempt once the user clicks again.
                git4idea.repo.GitRepositoryManager.getInstance(project).repositories.forEach { it.update() }
            }

            2 -> {
                Messages.showInfoMessage(
                    project,
                    "How to fix:\n\n" +
                        "1) Open the repo root folder (it must contain a .git directory).\n" +
                        "2) If it’s not a git repo, initialize or enable Git via:\n" +
                        "   VCS → Enable Version Control Integration… → Git\n" +
                        "3) If you just opened the project, wait for indexing to finish and try again.\n",
                    "How to Fix: No Git Repository"
                )
            }
        }
    }
}
