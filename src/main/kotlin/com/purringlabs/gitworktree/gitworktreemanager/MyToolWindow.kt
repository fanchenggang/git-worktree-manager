package com.purringlabs.gitworktree.gitworktreemanager

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.purringlabs.gitworktree.gitworktreemanager.ui.WorktreeManagerContent
import org.jetbrains.jewel.bridge.addComposeTab

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab(
            MyMessageBundle.message("toolwindow.stripe.GitWorktrees"),
            focusOnClickInside = true
        ) {
            WorktreeManagerContent(project)
        }
    }
}
