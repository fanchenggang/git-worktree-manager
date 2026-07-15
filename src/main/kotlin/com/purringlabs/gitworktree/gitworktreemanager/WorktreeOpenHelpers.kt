package com.purringlabs.gitworktree.gitworktreemanager

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import java.io.File

internal fun openOrFocusWorktree(
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
