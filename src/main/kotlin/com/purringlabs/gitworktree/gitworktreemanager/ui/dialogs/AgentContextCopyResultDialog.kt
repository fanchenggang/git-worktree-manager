package com.purringlabs.gitworktree.gitworktreemanager.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle
import com.purringlabs.gitworktree.gitworktreemanager.models.AgentContextCopyResult
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

class AgentContextCopyResultDialog(
    project: Project,
    private val result: AgentContextCopyResult
) : DialogWrapper(project) {
    init {
        title = MyMessageBundle.message("dialog.agentContext.resultsTitle")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.add(
            JBLabel(
                MyMessageBundle.message(
                    "dialog.agentContext.summary",
                    result.copiedCount,
                    result.skippedCount,
                    result.failureCount
                )
            ),
            BorderLayout.NORTH
        )

        val details = buildString {
            if (result.copied.isNotEmpty()) {
                appendLine(MyMessageBundle.message("dialog.agentContext.copiedHeader"))
                result.copied.forEach { appendLine("- $it") }
                appendLine()
            }
            if (result.skipped.isNotEmpty()) {
                appendLine(MyMessageBundle.message("dialog.agentContext.skippedHeader"))
                result.skipped.forEach { (item, reason) -> appendLine("- $item: $reason") }
                appendLine()
            }
            if (result.failed.isNotEmpty()) {
                appendLine(MyMessageBundle.message("dialog.agentContext.failedHeader"))
                result.failed.forEach { (item, reason) -> appendLine("- $item: $reason") }
            }
        }

        val textArea = JTextArea(details).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val scrollPane = JBScrollPane(textArea)
        scrollPane.preferredSize = Dimension(560, 260)
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    override fun createActions() = arrayOf(okAction)
}
