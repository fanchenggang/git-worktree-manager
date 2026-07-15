package com.purringlabs.gitworktree.gitworktreemanager.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle

object NoRepositoryUiHelper {

    fun showNoRepositoryDialog(
        project: Project,
        @Suppress("UNUSED_PARAMETER") attemptedOperation: String
    ) {
        val choice = Messages.showDialog(
            project,
            MyMessageBundle.message("dialog.noRepo.message"),
            MyMessageBundle.message("dialog.noRepo.title"),
            arrayOf(
                MyMessageBundle.message("dialog.noRepo.openGit"),
                MyMessageBundle.message("dialog.noRepo.refresh"),
                MyMessageBundle.message("dialog.noRepo.howToFix")
            ),
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
                    MyMessageBundle.message("dialog.noRepo.howto.message"),
                    MyMessageBundle.message("dialog.noRepo.howto.title")
                )
            }
        }
    }
}
